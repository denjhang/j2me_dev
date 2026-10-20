VgmBrowser v1.1.1 (bin)

Fix for no-sound on Nokia real devices (Symbian^3/Belle, e.g. E5):
- MIDIControl is now obtained AFTER player.start() (MeBoy-compatible order);
  on Nokia the control grabbed before STARTED is a silent no-op shell
- Removed prefetch() from the init path
- CC7 now initialized to master volume (100) instead of 0
