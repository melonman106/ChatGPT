# Eagler 26.2 ModsPack

This directory is the build-time input for ported Eagler client mods and resource-pack mods.

## Current package format

`apply_vbv_patch.py` currently imports `.zip` files as resource-pack client mods. Each ZIP may contain `pack.mcmeta`, `pack.png`, `assets/`, and `data/`.

## Planned native-mod package format

Ported Java mods will use an Eagler-specific package rather than being loaded as arbitrary Fabric JARs. The package will carry metadata such as:

- `id`
- `name`
- `version`
- `minecraft`
- `eagler`
- `dependencies`
- `authors`
- `description`
- `icon`
- `type` (`native` or `resource-pack`)

The native package will contain code that has already been compiled for the Eagler/TeaVM runtime. Fabric Loader and the full Fabric runtime are not required in the browser client.

## Website distribution

A future GitHub Pages mod site can publish a signed/hashed registry of these packages. The Eagler client can then consume that registry through a normal web request and offer an in-game mod browser/downloader, subject to browser/CORS and package-size constraints.

The existing native Mods screen and generated `VBVGeneratedPackMods` registry are the starting point for that system.
