# 관리자 수동 재시도: 명령 실행 위치에서 가장 가까운 오버월드 플레이어를 기준으로 다시 배치한다.
data remove storage cobbleventure_bootstrap:state starter_town
execute as @a[gamemode=!spectator,sort=nearest,limit=1] at @s if dimension minecraft:overworld run function cobbleventure_bootstrap:place_starter_town
