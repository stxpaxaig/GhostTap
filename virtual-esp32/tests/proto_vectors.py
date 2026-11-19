from pathlib import Path

root = Path(__file__).resolve().parents[1]
sec0 = (root / "protocol/espressif-2.2.3/sec0.proto").read_text()
session = (root / "protocol/espressif-2.2.3/session.proto").read_text()
config = (root / "protocol/espressif-2.2.3/network_config.proto").read_text()
scan = (root / "protocol/espressif-2.2.3/network_scan.proto").read_text()
assert "S0SessionCmd" in sec0 and "S0SessionResp" in sec0 and "Sec0Payload sec0 = 10" in session
assert "CmdSetWifiConfig cmd_set_wifi_config = 12" in config
assert "RespSetWifiConfig resp_set_wifi_config = 13" in config
assert "CmdScanWifiStart cmd_scan_wifi_start = 10" in scan
assert "RespScanWifiResult resp_scan_wifi_result = 15" in scan

def v(n):
    out = bytearray()
    while n > 127:
        out.append((n & 127) | 128)
        n >>= 7
    out.append(n)
    return bytes(out)

def field(number, payload):
    return v((number << 3) | 2) + v(len(payload)) + payload

# SessionData(sec0=Sec0Payload(msg=1, sr={})); sc→sr response field is 21.
session_response = field(10, v(8) + v(1) + field(21, b""))
assert session_response == bytes.fromhex("52 05 08 01 aa 01 00")
# NetworkConfigPayload(msg=3, resp_set_wifi_config={status:Success}).
config_response = v(8) + v(3) + field(13, v(8) + v(0))
assert config_response == bytes.fromhex("08 03 6a 02 08 00")
print("PASS: source schema field numbers and Security 0/config protobuf wire vectors")
