#!/usr/bin/env python3
"""Check KG-next's README and verified default model against the canonical catalog."""
import json
from pathlib import Path
from katago_asset_catalog import load_catalog


def validate(root: Path) -> None:
    catalog = load_catalog(root / 'src/main/resources/katago-assets.json')
    model = catalog['models'][catalog['defaultModelId']]
    proof = json.loads((root / 'docs/KG_MODEL_VERIFICATION.json').read_text(encoding='utf-8'))[0]
    for key in ('fileName', 'sizeBytes', 'sha256'):
        if model[key] != proof[key]:
            raise ValueError(f'Default model {key} differs from verified bytes')
    if model['downloadUrl'] != proof['url']:
        raise ValueError('Default model URL differs from verified download')
    readme = (root / 'README.md').read_text(encoding='utf-8')
    values = [f"KataGo v{catalog['katagoVersion']}", catalog['katagoSourceCommit'],
              model['fileName'], str(model['sizeBytes']), model['sha256'],
              str(proof['rawSizeBytes']), proof['rawSha256']]
    for value in values:
        if value not in readme:
            raise ValueError(f'README metadata missing or outdated: {value}')
    if not readme.startswith('# KG-next\n'):
        raise ValueError('Main README must identify KG-next')
    for path in root.glob('README*.md'):
        content = path.read_text(encoding='utf-8')
        if 'v1.18.1' in content or 'b11c768h12nbt3tflrs-fson-silu.bin.gz' in content:
            raise ValueError(f'Stale current engine/model documentation: {path.name}')


if __name__ == '__main__':
    validate(Path(__file__).resolve().parents[1])
    print('KG-next engine/model/README metadata: PASS')
