"""Downloads the voice gender model (Apache-2.0) used by gender.py into models/ (not committed)."""
import urllib.request

from gender import MODEL_PATH

URL = "https://huggingface.co/prithivMLmods/Common-Voice-Gender-Detection-ONNX/resolve/main/onnx/model_int8.onnx"

if __name__ == "__main__":
    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    if MODEL_PATH.exists():
        print("already downloaded:", MODEL_PATH)
    else:
        urllib.request.urlretrieve(URL, MODEL_PATH)
        print("downloaded:", MODEL_PATH, MODEL_PATH.stat().st_size // 1_000_000, "MB")
