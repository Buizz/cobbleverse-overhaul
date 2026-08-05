# 같은 월드에서 중복 예약되지 않도록 표시하고 5초 뒤 배치를 시도한다.
data modify storage cobbleventure_bootstrap:state starter_town.scheduled set value 1b
schedule function cobbleventure_bootstrap:auto_place_starter_town 5s replace
