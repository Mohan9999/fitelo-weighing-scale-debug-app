# Sample capture logs

Raw BLE advertisement logs produced by the app (tab-separated), used to derive and verify the
findings in the main [README](../README.md).

Each matched line is:

```
elapsed_ms <TAB> iso_time <TAB> rssi <TAB> link_addr <TAB> name <TAB> MATCH=<name|sig|both>
            <TAB> MFG=<companyId>:<13 data bytes hex> <TAB> RAW=<full advertisement hex>
```

## `contact-vs-nocontact_2026-05-30.txt`

A single session with **two scans** at the same locked weight (78.5 kg):

- **Scan 1 — feet ON the electrode pads (contact):** every packet shows `... 13 88 00 02 ...`
  → contact flag `d2d3 = 5000`.
- **Scan 2 — feet NOT touching the electrodes (socks/shoes):** every packet shows
  `... 00 00 00 02 ...` → contact flag `d2d3 = 0`.

Only the contact flag differs (373 packets at `5000`, 447 at `0` — a perfect 1:1 split with
contact). The weight bytes are identical and no other field carries body data — confirming the
scale broadcasts **weight + a binary electrode-contact flag only**, never an impedance value.
