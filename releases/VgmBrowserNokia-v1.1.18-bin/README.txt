v1.1.18 (rebuilt): true parallel double buffering, PC-verified state machine (DbTest4 PASS)
 - Filling is NOT rate-limited: producer converts VGM at full CPU speed, timestamps use song virtual time
 - Prebuffer = 2: both BufA and BufB must be FULL before the first note
 - While one buffer plays, the other refills (real fill%/ready/play% shown in debug rows)
 - Visualization/progress driven by register replay of the playing segment (separate vis instance, no crosstalk)
 - Gen size 4k-256k, per-chunk synth re-init (initEvents), PC channel map
