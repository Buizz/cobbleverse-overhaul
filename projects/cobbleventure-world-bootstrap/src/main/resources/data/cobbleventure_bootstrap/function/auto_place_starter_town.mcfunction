# 오버월드 플레이어 한 명을 기준으로 배치한다. 없으면 예약 표시를 지워 다음 입장에서 재시도한다.
execute as @a[gamemode=!spectator] at @s if dimension minecraft:overworld unless data storage cobbleventure_bootstrap:state starter_town.attempted run function cobbleventure_bootstrap:place_starter_town
execute unless data storage cobbleventure_bootstrap:state starter_town.attempted run data remove storage cobbleventure_bootstrap:state starter_town.scheduled
