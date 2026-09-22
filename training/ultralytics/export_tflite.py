"""Export a trained YOLO .pt checkpoint to a TensorFlow Lite (.tflite) model.

The Android app loads the .tflite artifact via the TensorFlow Lite runtime. This script wraps the
`yolo export format=tflite` step so the settings that matter for the app (NMS baked into the graph,
confidence/IoU thresholds, max detections) are explicit and consistent between runs, instead of
being retyped on the command line each time.
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Convert a YOLO .pt checkpoint to a .tflite file for mobile."
    )
    parser.add_argument(
        "--weights",
        required=True,
        help="Path to the trained .pt checkpoint (e.g. runs/detect/<run>/weights/best.pt).",
    )
    parser.add_argument(
        "--imgsz",
        type=int,
        default=640,
        help="Square input image size used for the export.",
    )
    parser.add_argument(
        "--conf",
        type=float,
        default=0.25,
        help="Confidence threshold baked into the exported model's NMS.",
    )
    parser.add_argument(
        "--iou",
        type=float,
        default=0.45,
        help="IoU threshold baked into the exported model's NMS.",
    )
    parser.add_argument(
        "--max-det",
        type=int,
        default=300,
        help="Maximum detections per image baked into the exported model's NMS.",
    )
    parser.add_argument(
        "--output",
        help=(
            "Output .tflite path. Defaults to <weights_dir>/<weights_stem>.tflite."
        ),
    )
    return parser.parse_args()


def resolve_output(weights_path: Path, output: str | None) -> Path:
    """Return the output path for the converted TFLite model."""
    if output:
        return Path(output)
    return weights_path.with_suffix(".tflite")


def main() -> None:
    args = parse_args()

    try:
        from ultralytics import YOLO
    except ImportError as exc:
        raise SystemExit(
            "ultralytics is not installed. Run `pip install -r requirements.txt`."
        ) from exc

    weights_path = Path(args.weights)
    if not weights_path.exists():
        raise SystemExit(f"Weights not found: {weights_path}")

    output_path = resolve_output(weights_path, args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    model = YOLO(str(weights_path))

    # half=True picks the float16 variant (the one the Android app is built for). Ultralytics also
    # writes a float32 sibling and the intermediate SavedModel/ONNX files under
    # <weights_dir>/<weights_stem>_saved_model/ as a side effect; those are left in place (they are
    # git-ignored, see training/ultralytics/README.md) and only the float16 file is copied out here.
    exported = model.export(
        format="tflite",
        imgsz=args.imgsz,
        half=True,
        nms=True,
        conf=args.conf,
        iou=args.iou,
        max_det=args.max_det,
    )

    exported_path = Path(exported)
    if not exported_path.exists():
        raise SystemExit(f"Export reported success but file is missing: {exported_path}")

    shutil.copy2(exported_path, output_path)
    print(f"Saved TensorFlow Lite model to: {output_path}")


if __name__ == "__main__":
    main()
