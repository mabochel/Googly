#!/usr/bin/env python3
"""A stand-in for the Mac app's phone server, written independently from Shared/*.swift.

It speaks the same protocol (challenge → HMAC proof → HKDF direction keys → AES-GCM records) using
Python's `cryptography` package, so the Android code can be checked against a second implementation.

    python3 scripts/mock_mac.py            # prints the port and a pairing code, serves one phone
"""
import base64, json, os, socket, sys
from cryptography.hazmat.primitives import hashes, hmac
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


def hkdf(secret, salt, info):
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=info).derive(secret)


def proof(secret, challenge):
    h = hmac.HMAC(secret, hashes.SHA256())
    h.update(b"bluey-pair-v1" + challenge)
    return h.finalize()


class Channel:
    def __init__(self, secret, challenge):
        self.send_key = AESGCM(hkdf(secret, challenge, b"bluey-v1-mac-to-phone"))
        self.recv_key = AESGCM(hkdf(secret, challenge, b"bluey-v1-phone-to-mac"))
        self.sent = self.received = 0

    def seal(self, packet):
        nonce = os.urandom(12)
        record = json.dumps({"sequence": self.sent, "packet": packet}, separators=(",", ":")).encode()
        self.sent += 1
        return base64.b64encode(nonce + self.send_key.encrypt(nonce, record, None))

    def open(self, line):
        data = base64.b64decode(line, validate=True)
        record = json.loads(self.recv_key.decrypt(data[:12], data[12:], None))
        assert record["sequence"] == self.received, "out of order"
        self.received += 1
        return record["packet"]


def main():
    secret = os.urandom(32)
    pairing = {"version": 1, "service": "Mock Mac", "secret": base64.b64encode(secret).decode()}
    qr = "bluey-pair:" + base64.b64encode(json.dumps(pairing).encode()).decode()
    server = socket.socket()
    server.bind(("127.0.0.1", int(os.environ.get("PORT", "0"))))
    server.listen(1)
    print(server.getsockname()[1], qr, flush=True)
    conn, _ = server.accept()
    conn.settimeout(10)
    reader = conn.makefile("rb")
    send = lambda b: conn.sendall(b + b"\n")

    challenge = os.urandom(32)
    send(json.dumps({"challenge": base64.b64encode(challenge).decode()}).encode())
    got = base64.b64decode(json.loads(reader.readline())["proof"])
    if got != proof(secret, challenge):
        sys.exit("proof did not verify")
    channel = Channel(secret, challenge)
    send(channel.seal({"command": "paired"}))
    send(channel.seal({"hello": "Mock Mac"}))
    send(channel.seal({"face": {"gazeX": -0.4, "gazeY": 0.2, "mood": "thinking", "talk": 0}}))
    state = {"current": {"id": "3F2504E0-4F89-11D3-9A0C-0305E82C3301", "task": "Write the Android port",
                         "duration": 1500, "startedAt": 783612000.0, "elapsed": 10, "status": "running",
                         "detail": "First check in 30 seconds", "checks": []},
             "history": [], "revision": 1, "sentAt": 783612010.0}
    send(channel.seal({"command": "focusState", "text": json.dumps(state)}))

    seen = []
    for _ in range(3):
        packet = channel.open(reader.readline().strip())
        seen.append(packet)
        if packet.get("command") == "realtimeToken":
            send(channel.seal({"command": "realtimeToken", "callID": packet["callID"], "text": "ek_mock"}))
    assert seen[0] == {"hello": "Android test"}, seen[0]
    assert seen[1] == {"command": "focusSync"}, seen[1]
    assert seen[2]["command"] == "realtimeToken" and seen[2]["callID"] == "CALL-1", seen[2]
    print("mock Mac: phone authenticated, 3 packets verified", flush=True)
    reader.readline()  # wait for the phone to hang up


if __name__ == "__main__":
    main()
