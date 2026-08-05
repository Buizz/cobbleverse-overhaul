# 플레이어와 겹치지 않도록 X/Z +32 지점의 지표면에 BCA 소형 마을을 배치한다.
data modify storage cobbleventure_bootstrap:state starter_town set value {attempted:1b,placed:0b}
execute positioned ~32 ~ ~32 positioned over motion_blocking_no_leaves store success storage cobbleventure_bootstrap:state starter_town.placed byte 1 run place structure bca:village/default_small ~ ~ ~
execute if data storage cobbleventure_bootstrap:state {starter_town:{placed:1b}} run tellraw @s [{"text":"[Cobbleventure] ","color":"green"},{"text":"시작 지점 근처에 테스트 마을을 생성했습니다.","color":"white"}]
execute unless data storage cobbleventure_bootstrap:state {starter_town:{placed:1b}} run tellraw @s [{"text":"[Cobbleventure] ","color":"red"},{"text":"테스트 마을 배치에 실패했습니다. 다른 지형에서 /function cobbleventure_bootstrap:retry_starter_town 을 실행하세요.","color":"white"}]
