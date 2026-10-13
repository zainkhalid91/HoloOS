# One-time setup for the hand tracker: Python venv, MediaPipe, OpenCV, and the two models.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
python -m venv "$here\.venv"
& "$here\.venv\Scripts\python.exe" -m pip install --upgrade pip
& "$here\.venv\Scripts\python.exe" -m pip install mediapipe opencv-python numpy
$models = "$here\models"
New-Item -ItemType Directory -Force $models | Out-Null
Invoke-WebRequest "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task" -OutFile "$models\hand_landmarker.task"
Invoke-WebRequest "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task" -OutFile "$models\pose_landmarker_lite.task"
Write-Host "Tracker ready."
