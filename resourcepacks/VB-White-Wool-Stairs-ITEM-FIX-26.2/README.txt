This is the corrected ITEM-ONLY version.

It does NOT override quartz_stairs block models, so normal Quartz Stairs remain Quartz.

It reproduces the ViaBackwards Plus item logic:
custom_model_data 875 on the translated quartz_stairs item -> white_wool_stairs.

IMPORTANT:
This fixes inventory/in-hand rendering only. A normal Minecraft/Eagler resource pack cannot distinguish a placed White Wool Stair from a real Quartz Stair when ViaBackwards sends both as quartz_stairs. A separate block mapping/placeholder is required for placed-block rendering.
