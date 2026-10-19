#!/usr/bin/env python3
"""Generate synthetic inference expectations with the official XGBoost 3.2.0 CPU scorer.

No game records, training examples, or Java prediction code are used. This checks
model loading and inference arithmetic, not playing-strength calibration.
"""

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import xgboost as xgb


MODEL_SHA256 = "62e0634f4b028f081c1e40ad06bda1d14fbc6486ca75bb37436b9d4e8623be19"
TOLERANCE = 2e-5


def inputs(trees):
    ordinary = [
        .43, .66, .78, .38, .40, .71, .86, .64, .92, .98,
        .72, .62, .58, .70, .69, .73, .25, .41, .37, .45,
        1.15, 1.60, 1.85, .55, .29, .06, .04, .10,
        2.60, .40, .91, .85, 2.10, .35, .94, .89, 1.80, .30, .96, .92,
    ]
    low_loss = [
        .72, .86, .93, .80, .75, .94, .97, .86, .99, 1.,
        .96, .91, .60, .97, .94, .96, .43, .56, .52, .56,
        .14, .16, .17, .11, .48, 0., 0., .01,
        1.70, .35, .96, .95, 1.10, .25, .97, .96, .90, .20, .98, .97,
    ]
    high_loss = [
        .15, .32, .46, .17, .20, .37, .61, .30, .73, .89,
        .31, .16, .43, .43, .31, .40, .06, .16, .13, .20,
        1.90, 2.90, 4.30, 1.20, .37, .24, .16, .32,
        4.20, .80, .87, .72, 4.50, .86, .83, .68, 4.80, .91, .80, .64,
    ]
    rows = [
        {"id": "all_zero", "features": [0.] * 40},
        {"id": "all_one", "features": [1.] * 40},
        {"id": "ordinary", "features": ordinary},
        {"id": "low_loss", "features": low_loss},
        {"id": "high_loss", "features": high_loss},
        {"id": "all_missing", "features": [None] * 40},
    ]
    for name, missing in (
        ("missing_human_difficulty", {29, 33, 37}),
        ("missing_phase_rates", {13, 14, 15}),
        ("mixed_missing", set(range(0, 40, 3))),
    ):
        rows.append({
            "id": name,
            "features": [None if i in missing else v for i, v in enumerate(ordinary)],
        })

    # Root splits avoid an unproven requirement to reach a deeper node. Tree 0's
    # JSON threshold rounds up to float32; tree 4's rounds down. Double inputs
    # adjacent to either value distinguish float32 routing from direct double
    # comparisons without relying on random sampling to land near a split.
    for tree_id in (0, 4):
        tree = trees[tree_id]
        feature = tree["split_indices"][0]
        decimal = float(tree["split_conditions"][0])
        threshold = np.float32(decimal)
        boundary_values = (
            ("float32_below", float(np.nextafter(threshold, np.float32(-np.inf)))),
            ("float32_at", float(threshold)),
            ("float32_above", float(np.nextafter(threshold, np.float32(np.inf)))),
            ("json_double_below", float(np.nextafter(decimal, -np.inf))),
            ("json_double_at", decimal),
            ("json_double_above", float(np.nextafter(decimal, np.inf))),
            ("float32_double_below", float(np.nextafter(float(threshold), -np.inf))),
            ("float32_double_above", float(np.nextafter(float(threshold), np.inf))),
        )
        for label, value in boundary_values:
            features = ordinary.copy()
            features[feature] = value
            rows.append({
                "id": f"tree_{tree_id}_{label}",
                "boundary": {
                    "tree": tree_id,
                    "feature_index": feature,
                    "json_threshold": decimal,
                    "float32_threshold": float(threshold),
                    "relation": label,
                },
                "features": features,
            })
    return rows


def generate(model_path):
    if xgb.__version__ != "3.2.0":
        raise ValueError(f"Expected XGBoost 3.2.0, found {xgb.__version__}")
    raw = model_path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != MODEL_SHA256:
        raise ValueError("The bundled HumanSL40 model differs from the pinned oracle model")
    model = json.loads(raw)
    learner = model["learner"]
    names = learner["feature_names"]
    trees = learner["gradient_booster"]["model"]["trees"]
    assert model["version"] == [3, 2, 0]
    assert len(names) == 40 and len(trees) == 400
    assert learner["objective"]["name"] == "reg:absoluteerror"
    rows = inputs(trees)
    data = np.asarray(
        [[np.nan if v is None else v for v in row["features"]] for row in rows],
        dtype=np.float64,
    )
    booster = xgb.Booster(params={"device": "cpu", "nthread": 1}, model_file=model_path)
    matrix = xgb.DMatrix(data, feature_names=names, missing=np.nan, nthread=1)
    raw_predictions = booster.predict(matrix, output_margin=True)
    predictions = booster.predict(matrix)
    np.testing.assert_array_equal(raw_predictions, predictions)
    leaf_ids = booster.predict(matrix, pred_leaf=True)
    for i, row in enumerate(rows):
        raw_prediction = float(raw_predictions[i])
        row["expected_raw"] = raw_prediction
        row["expected_rank"] = max(0., min(12., raw_prediction))
        if "boundary" in row:
            tree = row["boundary"]["tree"]
            row["boundary"]["expected_leaf"] = int(leaf_ids[i, tree])
    return {
        "schema_version": 1,
        "model_sha256": MODEL_SHA256,
        "oracle": "Official XGBoost 3.2.0 CPU Booster.predict over a dense float64 DMatrix",
        "objective": learner["objective"]["name"],
        "absolute_tolerance": TOLERANCE,
        "missing_encoding": "JSON null means Double.NaN",
        "scope": "Synthetic model inference parity only; no game data or strength-accuracy validation",
        "feature_names": names,
        "cases": rows,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    fixture = generate(args.model)
    args.output.write_text(json.dumps(fixture, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(f"Wrote {len(fixture['cases'])} synthetic cases to {args.output}")


if __name__ == "__main__":
    main()
