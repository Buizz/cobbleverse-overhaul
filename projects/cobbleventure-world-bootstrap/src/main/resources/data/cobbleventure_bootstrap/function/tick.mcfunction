# 첫 오버월드 플레이어를 기준으로 월드당 한 번만 시작 마을 배치를 시도한다.
execute as @a[gamemode=!spectator] at @s if dimension minecraft:overworld unless data storage cobbleventure_bootstrap:state starter_town.attempted run function cobbleventure_bootstrap:place_starter_town
