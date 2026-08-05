# 첫 플레이어 입장 직후 주변 청크가 준비될 시간을 확보하도록 지연 배치를 예약한다.
execute unless data storage cobbleventure_bootstrap:state starter_town.attempted unless data storage cobbleventure_bootstrap:state starter_town.scheduled if entity @a[gamemode=!spectator,limit=1] run function cobbleventure_bootstrap:schedule_starter_town
