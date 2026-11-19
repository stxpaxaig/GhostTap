# Espressif 2.2.3 client reverse-map

The supplied app uses `BLETransport` over a primary service UUID selected from the advertisement. It enumerates GATT characteristics, reads User Description descriptor `0x2901`, and maps the UTF-8 value to an endpoint name. For a request, it writes the characteristic and then reads it to obtain the response. The phone-side device therefore needs a GATT server, named characteristics, and read-after-write response storage.

## Endpoint UUID suffix map

| Endpoint | Suffix | Protocol |
|---|---:|---|
| `prov-session` | `FF51` | `SessionData` and negotiated security scheme |
| `prov-config` | `FF52` | Wi-Fi/Thread configuration and status |
| `proto-ver` | `FF53` | UTF-8 JSON `prov` version, security, and capability information |
| `prov-scan` | `FF54` | Wi-Fi/Thread scan payloads and paged results |
| `prov-ctrl` | `FF55` | reset and reprovision controls |
| `custom-data` | `FF56` | application-defined opaque bytes |
| `virtual-state` | `FF57` | this project's JSON state format |
| `virtual-uart` | `FF58` | this project's UTF-8 terminal append operation |
| `virtual-gpio` | `FF59` | this project's JSON pin state operations |
| `virtual-flash` | `FF5A` | this project's JSON NVS operations |

The endpoint UUIDs are placed in the custom 128-bit service base `7ab1xxxx-6b3d-4f60-9e2f-13572468f00d`. The client uses the `0x2901` name map, so it does not require a fixed Espressif base UUID for each characteristic.

## Protocol coverage

- Security 0's initial empty `S0SessionCmd` arrives in `SessionData.sec0` field 10; the server returns `S0SessionResp` in the same field with `msg=1` and success status 0. It is plaintext and unauthenticated.
- Wi-Fi scan request enum 0 uses `cmd_scan_wifi_start` field 10; status enum 2 uses command field 12; results enum 4 uses command field 14. Responses use message enums 1/3/5 and oneof fields 11/13/15. This client requests pages of four access points.
- Wi-Fi config enum 2 uses `cmd_set_wifi_config` field 12 (SSID bytes field 1, passphrase bytes field 2). Apply enum 4 uses command field 14; status enum 0 uses command field 10. The virtual runtime persists credentials, then reports connected only when the phone itself is on the matching Wi-Fi SSID.
- Control enum 1 resets Wi-Fi state; enum 3 requests reprovisioning.
- Security 1 is Curve25519/AES-CTR; Security 2 is SRP6a/AES-GCM. They are not implemented or advertised. Thread provisioning is not advertised.

Schemas are copied with license into `protocol/espressif-2.2.3`. Device-side Java handles the covered wire messages through `ProtoWire`; the mobile app remains dependency-free.
