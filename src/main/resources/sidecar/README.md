# Sidecar binary

Drop the MITMConnect executable here; it is packaged into the jar and unpacked
at runtime by `org.bittrace.proxy.SidecarBinary`.

- Windows: `MITMConnect.exe`
- Linux / macOS: `MITMConnect` (must have the executable bit set before it is
  packaged — the extractor sets it again on the unpacked copy)

Only the binary for the platform the app runs on is looked up, so shipping both
names side by side is fine.

At runtime it is extracted to
`<java.io.tmpdir>/bittrace-sidecar-<content-hash>/MITMConnect[.exe]`, so a
rebuilt binary gets a fresh folder and a re-launch reuses the existing one.

To run against a binary on disk instead of the packaged one (handy while
iterating on the sidecar), start the app with:

    -Dbittrace.sidecar.dir=<folder containing MITMConnect[.exe]>
