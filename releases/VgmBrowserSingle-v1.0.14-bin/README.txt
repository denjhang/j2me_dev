VgmBrowserSingle v1.0.14 (2026-10-21)
=====================================

 - Health monitor split into two rows:
     Stream   level 4k  next prep
     Buffer   total 8k  max 8k
   (level = current chunk fill; state inline on row 1;
    total = buffered; max = watermark = 2 chunks)
 - GD3 scroll position no longer resets on track switch,
   so the health rows stay where you left them across tracks

All else same as v1.0.13. Works perfectly on E5 (S60).
