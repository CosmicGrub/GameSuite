package com.gamesuite.games.flickrink

/**
 * The 14 Flick Rink levels in the machine-readable level format (one JSON object per line, see docs/design/kids-games/flick-rink.md
 * section "Level format"). Units are rink units (600 wide, 960 tall, y grows downward). The parser is [FlickRinkLevels.parse].
 * The `par` values are designer estimates; the phase 1 solver test tightens them (open question Q4).
 * Generated and validated outside the build (flood fill reachability in every gate and slider state); do not hand-edit without redoing that check.
 */
internal object FlickRinkLevelData {
    const val FORMAT_VERSION = 1

    val LEVELS_JSONL: String = """
{"id":"rink01","name":"Open Ice","band":"little","par":6,"seed":1001,"fish":[[300,420],[150,300],[450,250]],"walls":[],"posts":[],"gates":[],"sliders":[],"slow":[],"puddles":[]}
{"id":"rink02","name":"Post Parade","band":"little","par":8,"seed":1002,"fish":[[300,200],[100,420],[500,420]],"walls":[],"posts":[[170,620,26],[430,620,26],[210,330,26]],"gates":[],"sliders":[],"slow":[],"puddles":[]}
{"id":"rink03","name":"Side Door","band":"little","par":9,"seed":1003,"fish":[[100,380],[300,200],[500,300]],"walls":[[0,520,330,520]],"posts":[],"gates":[],"sliders":[],"slow":[],"puddles":[]}
{"id":"rink04","name":"Snow Drifts","band":"little","par":9,"seed":1004,"fish":[[150,440],[450,270],[300,140]],"walls":[],"posts":[[300,580,28]],"gates":[],"sliders":[],"slow":[[40,380,220,140],[340,200,220,140]],"puddles":[]}
{"id":"rink05","name":"Zigzag","band":"medium","par":11,"seed":1005,"fish":[[100,300],[500,510],[300,160]],"walls":[[0,610,400,610],[200,430,600,430]],"posts":[],"gates":[],"sliders":[],"slow":[],"puddles":[]}
{"id":"rink06","name":"Puddle Path","band":"medium","par":12,"seed":1006,"fish":[[300,180],[110,300],[490,300]],"walls":[],"posts":[[300,540,24]],"gates":[],"sliders":[],"slow":[],"puddles":[[300,300,45],[180,500,40],[420,500,40]]}
{"id":"rink07","name":"The Gate","band":"medium","par":12,"seed":1007,"fish":[[300,300],[120,200],[480,200]],"walls":[[0,480,240,480],[360,480,600,480]],"posts":[],"gates":[{"id":"g1","seg":[240,480,360,480],"pad":[110,640,36]}],"sliders":[],"slow":[],"puddles":[]}
{"id":"rink08","name":"Slide Door","band":"medium","par":12,"seed":1008,"fish":[[150,300],[450,300],[300,160]],"walls":[],"posts":[],"gates":[],"sliders":[{"id":"s1","a":[0,520,300,520],"b":[300,520,600,520]}],"slow":[],"puddles":[]}
{"id":"rink09","name":"Pond Maze","band":"medium","par":14,"seed":1009,"fish":[[300,540],[100,380],[500,200]],"walls":[[200,640,600,640],[0,470,400,470],[200,300,600,300]],"posts":[],"gates":[],"sliders":[],"slow":[],"puddles":[[300,340,26],[520,555,34]]}
{"id":"rink10","name":"Two Gates","band":"big","par":15,"seed":1010,"fish":[[300,460],[100,260],[500,180]],"walls":[[0,560,250,560],[350,560,600,560],[0,360,250,360],[350,360,600,360]],"posts":[],"gates":[{"id":"g1","seg":[250,560,350,560],"pad":[80,660,34]},{"id":"g2","seg":[250,360,350,360],"pad":[520,460,34]}],"sliders":[],"slow":[[40,160,180,100]],"puddles":[]}
{"id":"rink11","name":"Windmill Walls","band":"big","par":15,"seed":1011,"fish":[[150,480],[450,480],[300,170]],"walls":[],"posts":[[300,490,28],[150,250,26],[450,250,26]],"gates":[],"sliders":[{"id":"s1","a":[100,630,300,630],"b":[300,630,500,630]},{"id":"s2","a":[300,370,500,370],"b":[100,370,300,370]}],"slow":[],"puddles":[]}
{"id":"rink12","name":"Grand Rink","band":"big","par":16,"seed":1012,"fish":[[300,540],[100,330],[500,330]],"walls":[[0,640,230,640],[370,640,600,640]],"posts":[[150,540,24],[450,540,24]],"gates":[{"id":"g1","seg":[230,640,370,640],"pad":[520,700,34]}],"sliders":[{"id":"s1","a":[0,440,300,440],"b":[300,440,600,440]}],"slow":[[40,160,200,100],[360,160,200,100]],"puddles":[[300,300,40]]}
{"id":"rink13","name":"Bank Shot","band":"big","par":16,"seed":1013,"fish":[[300,380],[300,230],[300,130]],"walls":[[110,500,490,500]],"posts":[[110,300,24],[490,300,24]],"gates":[],"sliders":[],"slow":[],"puddles":[]}
{"id":"rink14","name":"Champion Rink","band":"big","par":18,"seed":1014,"fish":[[120,560],[500,360],[300,120]],"walls":[[0,650,230,650],[370,650,600,650],[200,230,600,230]],"posts":[[300,540,26],[120,380,24]],"gates":[{"id":"g1","seg":[230,650,370,650],"pad":[530,740,32]}],"sliders":[{"id":"s1","a":[0,450,300,450],"b":[300,450,600,450]},{"id":"s2","a":[300,330,450,330],"b":[150,330,300,330]}],"slow":[[300,380,160,60]],"puddles":[[480,560,36],[130,250,34]]}
""".trimIndent()
}
