VgmBrowser v1.1.2 (bin)

File browser fixes for Nokia real devices:
- No more "open connection failed -5" at startup: the KEmulator-only preset
  path file:///root/vgm/ is now probed silently; on real devices the browser
  starts directly from the filesystem root list (C:/, E:/, MemoryCard, ...)
- Fixed "URL is not valid" when navigating up: directory URLs now always
  keep the trailing slash, and going up from a drive root returns to the
  root list instead of building an invalid URL
