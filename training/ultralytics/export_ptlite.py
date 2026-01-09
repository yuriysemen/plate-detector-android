"""Export a trained YOLO .pt checkpoint to a TorchScript Lite (.ptlite) model.

The Android app can load the .ptlite artifact via the PyTorch Lite runtime.
This script keeps the conversion steps explicit so it is easier to adjust
for different image sizes or devices.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import torch
from torch.utils.mobile_optimizer import optimize_for_mobile


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Convert a YOLO .pt checkpoint to a .ptlite file for mobile."
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
        help="Square input image size used for tracing the model.",
    )
    parser.add_argument(
        "--device",
        default="cpu",
        help="Device for tracing (cpu, cuda, or mps).",
    )
    parser.add_argument(
        "--output",
        help=(
            "Output .ptlite path. Defaults to <weights_dir>/<weights_stem>.ptlite."
        ),
    )
    return parser.parse_args()


def resolve_output(weights_path: Path, output: str | None) -> Path:
    """Return the output path for the converted Lite model."""
    if output:
        return Path(output)
    return weights_path.with_suffix(".ptlite")


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

    # Load the trained YOLO model and move it to the requested device.
    model = YOLO(str(weights_path))
    torch_model = model.model
    torch_model.to(args.device)
    torch_model.eval()

    # Trace with a dummy input to produce a TorchScript graph.
    example_input = torch.zeros(1, 3, args.imgsz, args.imgsz, device=args.device)
    traced = torch.jit.trace(torch_model, example_input)

    # Optimize for the PyTorch Lite interpreter and save a .ptlite artifact.
    optimized = optimize_for_mobile(traced)
    optimized._save_for_lite_interpreter(str(output_path))

    print(f"Saved TorchScript Lite model to: {output_path}")


if __name__ == "__main__":
    main()
