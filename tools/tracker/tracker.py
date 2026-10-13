"""
HoloOS hand tracker.

Reads the webcam, runs MediaPipe HandLandmarker (both hands) and PoseLandmarker (only the
shoulders are used, to anchor the interaction zone to the user's body), and streams the
landmarks to the HoloOS app over UDP on localhost.

Privacy: frames never leave this process. Nothing is written to disk, nothing is sent
except landmark coordinates, and the face is never transmitted (pose face points are
dropped; only the two shoulder points are sent).

Packet (little-endian):
  'HOLO' u8 version=1
  f64 capture_time_ms  (time.time()*1000 when the frame was grabbed)
  u32 frame_id
  f32 camera_fps
  u16 width u16 height
  u8  pose_valid   f32 lsx lsy rsx rsy    (shoulders, mirrored image coords 0..1)
  u8  hand_count
  per hand: u8 is_right  f32 score  21 x (f32 x, f32 y, f32 z)
"""
import argparse
import os

# Media Foundation opens the camera in ~1 s instead of ~10 s without hardware transforms.
os.environ.setdefault("OPENCV_VIDEOIO_MSMF_ENABLE_HW_TRANSFORMS", "0")

import socket
import struct
import threading
import time
from pathlib import Path

import cv2
import mediapipe as mp
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision

HERE = Path(__file__).resolve().parent


class LatestFrame:
    """Capture thread that keeps only the newest frame: never queue, never lag behind."""

    def __init__(self, index: int, width: int, height: int, fps: int):
        # Media Foundation delivers the webcam's full rate; DirectShow is a fallback
        # (on many laptop cameras it is limited to ~10 fps).
        self.cap = cv2.VideoCapture(index, cv2.CAP_MSMF)
        if not self.cap.isOpened():
            self.cap = cv2.VideoCapture(index, cv2.CAP_DSHOW)
            self.cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        self.cap.set(cv2.CAP_PROP_FPS, fps)
        self.cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        if not self.cap.isOpened():
            raise SystemExit(f"Camera {index} could not be opened")
        self.lock = threading.Lock()
        self.frame = None
        self.stamp = 0.0
        self.seq = 0
        self.fps = 0.0
        self.running = True
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        n, t0 = 0, time.perf_counter()
        while self.running:
            ok, f = self.cap.read()
            if not ok:
                time.sleep(0.005)
                continue
            stamp = time.time() * 1000.0
            with self.lock:
                self.frame, self.stamp, self.seq = f, stamp, self.seq + 1
            n += 1
            dt = time.perf_counter() - t0
            if dt >= 1.0:
                self.fps, n, t0 = n / dt, 0, time.perf_counter()

    def latest(self, after_seq: int):
        with self.lock:
            if self.seq == after_seq:
                return None
            return self.frame, self.stamp, self.seq


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--camera", type=int, default=0)
    ap.add_argument("--port", type=int, default=47474)
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--fps", type=int, default=60)
    ap.add_argument("--pose-every", type=int, default=3, help="run pose on every Nth frame")
    args = ap.parse_args()

    hands = vision.HandLandmarker.create_from_options(vision.HandLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(HERE / "models" / "hand_landmarker.task")),
        running_mode=vision.RunningMode.VIDEO,
        num_hands=2,
        min_hand_detection_confidence=0.5,
        min_hand_presence_confidence=0.5,
        min_tracking_confidence=0.5,
    ))
    pose = vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(HERE / "models" / "pose_landmarker_lite.task")),
        running_mode=vision.RunningMode.VIDEO,
        num_poses=1,
    ))

    cam = LatestFrame(args.camera, args.width, args.height, args.fps)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    dest = ("127.0.0.1", args.port)
    print(f"HoloOS tracker: camera {args.camera} -> udp://{dest[0]}:{dest[1]}", flush=True)

    seq, frame_id, last_ts = 0, 0, 0
    shoulders = None
    while True:
        got = cam.latest(seq)
        if got is None:
            time.sleep(0.001)
            continue
        frame, stamp, seq = got
        frame = cv2.flip(frame, 1)  # mirror: moving right moves right
        # Landmarks are normalised, so inference on a smaller copy is free accuracy-wise
        # at arm's length and roughly halves CPU time.
        small = cv2.resize(frame, (640, 360), interpolation=cv2.INTER_AREA) if frame.shape[1] > 640 else frame
        rgb = cv2.cvtColor(small, cv2.COLOR_BGR2RGB)
        image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
        ts = max(int(stamp), last_ts + 1)  # MediaPipe needs strictly increasing timestamps
        last_ts = ts

        result = hands.detect_for_video(image, ts)
        if frame_id % args.pose_every == 0:
            p = pose.detect_for_video(image, ts)
            if p.pose_landmarks:
                lm = p.pose_landmarks[0]
                # 11 = left shoulder, 12 = right shoulder (subject's own sides).
                shoulders = (lm[11].x, lm[11].y, lm[12].x, lm[12].y)
            else:
                shoulders = None

        out = bytearray(b"HOLO")
        out += struct.pack("<BdIfHH", 1, stamp, frame_id, cam.fps, frame.shape[1], frame.shape[0])
        if shoulders:
            out += struct.pack("<B4f", 1, *shoulders)
        else:
            out += struct.pack("<B4f", 0, 0, 0, 0, 0)
        n = len(result.hand_landmarks)
        out += struct.pack("<B", n)
        for i in range(n):
            cat = result.handedness[i][0]
            # The image is mirrored, so MediaPipe's label already matches the user's hand.
            out += struct.pack("<Bf", 1 if cat.category_name == "Right" else 0, cat.score)
            for pt in result.hand_landmarks[i]:
                out += struct.pack("<3f", pt.x, pt.y, pt.z)
        sock.sendto(out, dest)
        frame_id += 1


if __name__ == "__main__":
    main()
