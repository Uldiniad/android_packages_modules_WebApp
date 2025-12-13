#!/usr/bin/env python3
#
# Copyright (C) 2025 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# This script builds an intermediate bundle for WebApp on-device minting.
#

import os
import sys
import zipfile

def print_usage_and_exit():
    print(f'''
Expected args in order:
1. webapp-template.zip: The output zip template bundle
2. AndroidManifest.xml.mustache: The AndroidManifest.xml Mustache template
3. colors.xml.mustache: The colors.xml Mustache template
4. ic_launcher.xml: The adaptive icon xml
5. WebAppTemplate.apk: The template APK containing classes.dex
6. res-ids.txt: The stable resource IDs file
7+. res/...: One or more resource files
''', file=sys.stderr)
    sys.exit(1)

def main():
    if len(sys.argv) < 7:
        print(f'There are no enough args', file=sys.stderr)
        print_usage_and_exit()

    out_zip = sys.argv[1]
    if os.path.basename(out_zip) != 'webapp-template.zip':
        print(f'Arg 1 must be webapp-template.zip, but is {out_zip}', file=sys.stderr)
        print_usage_and_exit()

    android_manifest = sys.argv[2]
    if os.path.basename(android_manifest) != 'AndroidManifest.xml.mustache':
        print(f'Arg 2 must be AndroidManifest.xml.mustache, but is {android_manifest}', file=sys.stderr)
        print_usage_and_exit()

    colors_xml = sys.argv[3]
    if os.path.basename(colors_xml) != 'colors.xml.mustache':
        print(f'Arg 3 must be colors.xml.mustache, but is {colors_xml}', file=sys.stderr)
        print_usage_and_exit()

    ic_launcher = sys.argv[4]
    if os.path.basename(ic_launcher) != 'ic_launcher.xml':
        print(f'Arg 4 must be ic_launcher.xml, but is {ic_launcher}', file=sys.stderr)
        print_usage_and_exit()

    template_apk = sys.argv[5]
    if os.path.basename(template_apk) != 'WebAppTemplate.apk':
        print(f'Arg 5 must be WebAppTemplate.apk, but is {template_apk}', file=sys.stderr)
        print_usage_and_exit()

    res_ids = sys.argv[6]
    if os.path.basename(res_ids) != 'res-ids.txt':
        print(f'Arg 6 must be res-ids.txt, but is {res_ids}', file=sys.stderr)
        print_usage_and_exit()

    res_files = sys.argv[7:]
    for res_file in res_files:
        if res_file.split('/')[-3] != 'res':
            print(f'Args starting from 7 must be res files, but got {res_file}', file=sys.stderr)
            print_usage_and_exit()

    with zipfile.ZipFile(out_zip, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        # 1. AndroidManifest.xml and colors.xml Mustache templates
        out.write(android_manifest, 'AndroidManifest.xml.mustache')
        out.write(colors_xml, 'colors.xml.mustache')

        # 2. ic_launcher.xml for adaptive icon
        out.write(ic_launcher, 'ic_launcher.xml')

        # 3. res-ids.txt for stable resource IDs
        out.write(res_ids, 'res-ids.txt')

        # 4. classes.dex from WebAppTemplate.apk
        with zipfile.ZipFile(template_apk, 'r') as template_zip:
            with template_zip.open('classes.dex') as classes_dex:
                out.writestr('classes.dex', classes_dex.read())

        # 5. All res resources
        for res_file in res_files:
            # We want to store them as `res/...` in the zip.
            arcname = os.path.join('res', *res_file.split(os.path.sep)[-2:])
            if 'res' in res_file:
                components = res_file.split('/')
                res_index = components.index('res')
                arcname = '/'.join(components[res_index:])
                out.write(res_file, arcname)


if __name__ == '__main__':
    main()
