"""Catalog-bound engine inventories, independent of the GUI release destination."""

try:
    from scripts.katago_asset_catalog import validate_catalog, asset_origin_for, asset_source_identity
    from scripts.katago_source_targets import SOURCE_COMMIT, TARGETS
    from scripts.validate_release_workflow_identity import require
except ModuleNotFoundError:
    from katago_asset_catalog import validate_catalog, asset_origin_for, asset_source_identity
    from katago_source_targets import SOURCE_COMMIT, TARGETS
    from validate_release_workflow_identity import require


def source_asset_groups(catalog):
    validate_catalog(catalog)
    if catalog.get('origin') == 'project-source-build':
        independent = {key for key, value in catalog['assets'].items() if 'katagoSourceCommit' in value}
        inherited = {key for key in catalog['assets']
                     if asset_origin_for(catalog, key) == 'project-source-build' and key not in independent}
        require(catalog.get('katagoSourceCommit') == SOURCE_COMMIT, 'Unexpected source commit')
        require(inherited == set(TARGETS) - independent, 'Incomplete inherited source target matrix')
    groups = {}
    for key, asset in catalog['assets'].items():
        if asset_origin_for(catalog, key) != 'project-source-build':
            continue
        identity = asset_source_identity(catalog, key)
        records = groups.setdefault((identity['releaseRepository'], identity['releaseTag']), {})
        require(asset['assetName'] not in records, 'Duplicate catalog asset name')
        records[asset['assetName']] = asset
    return groups


def verify_source_inventory(rows, records):
    expected = {record['assetName']: record for record in records.values()}
    require(len(expected) == len(records), 'Duplicate catalog asset name')
    selected = [row for row in rows if row.get('name') in expected]
    require(len(selected) == len(expected) and len({row['name'] for row in selected}) == len(expected),
            'Missing or duplicate source archive')
    for row in selected:
        record = expected[row['name']]
        require(row.get('state') == 'uploaded' and row.get('size') == record['sizeBytes']
                and row.get('digest') == 'sha256:' + record['sha256'],
                'Source archive metadata differs from pinned catalog')
