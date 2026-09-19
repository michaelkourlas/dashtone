import json
import os
import sys

# argv: <variant> [output path]. The output path defaults to the old in-source location so
# running this by hand still works; the build passes a path under app/build/generated.
scriptDir = os.path.dirname(os.path.abspath(__file__))
variant = sys.argv[1]
outputPath = sys.argv[2] if len(sys.argv) > 2 else 'src/main/assets/PACKAGE_LICENSES.md'

licenseMap = {
    # Apache License 2.0
    'Apache 2.0': 'Apache-2.0.txt',
    'Apache License 2.0': 'Apache-2.0.txt',
    'Apache License, Version 2.0': 'Apache-2.0.txt',
    'Apache-2.0': 'Apache-2.0.txt',
    'The Apache License, Version 2.0': 'Apache-2.0.txt',
    'The Apache Software License, Version 2.0': 'Apache-2.0.txt',
    'Apache License v2': 'Apache-2.0.txt',

    # BSD License
    'BSD-3-Clause': 'BSD-3-Clause.txt',
}

licenseGroupMap = {
    ('The MIT License', 'org.checkerframework', 'checker-qual', '3.43.0'): 'checker-qual.txt',
    ('MIT License', 'com.github.mukeshsolanki', 'MarkdownView-Android', '2.0.0'): 'MarkdownView-Android.txt'
}

dependencies = json.load(open(f'build/generated/oss_licenses/{variant}/dependencies_with_licenses.json'))
dependencies.sort(key=lambda e: e['name'])
for entry in dependencies:
    for l in entry['licenses']:
        if l['text'] is None:
            fileName = None
            if l['name'] in licenseMap:
                fileName = licenseMap[l['name']]
            else:
                artifactInfo = entry['artifactInfo']
                if artifactInfo is not None:
                    key = (l['name'], artifactInfo['group'], artifactInfo['name'], artifactInfo['version'])
                    if key in licenseGroupMap:
                        fileName = licenseGroupMap[key]
            if fileName is not None:
                l['text'] = open(os.path.join(scriptDir, fileName)).read()
            else:
                raise Exception(entry)

os.makedirs(os.path.dirname(os.path.abspath(outputPath)), exist_ok=True)
with open(outputPath, 'w', encoding='utf-8') as f:
    for entry in dependencies:
        artifactInfo = entry['artifactInfo']
        if artifactInfo is not None:
            f.write(f'## {entry["name"]} ({artifactInfo["group"]}:{artifactInfo["name"]}:{artifactInfo["version"]})')
        else:
            f.write(f'## {entry["name"]}')
        for l in entry['licenses']:
            f.write('\n\n```\n')
            f.write(l['text'].strip('\n'))
            f.write('\n```\n\n')