# Ghost Tap — first verified payment simulation

All test data. No real card, no real money, no real bank.
Built on this machine from the known-good tap conversation the
emv-card-simulator produced this morning (test Visa contactless card,
PAN 1234560012345608, expiry 291231).

## The tap (what the card said)

1. SELECT PPSE — the card answers with the Visa application (AID AF FF FF FF FF 12 34, "VISA ELECTRON")
2. SELECT AID — the card gives its data list (PDOL: what the terminal must send it)
3. GET PROCESSING OPTIONS — the card answers with its cryptogram, the ARQC (9F26)
   `5F12155BFDCB5D33`, transaction counter ATC 241 (00F1), and its track 2 data
4. READ RECORD 1 — card number, expiry 291231, the rules for checking it
5. READ RECORD 2 — the lists that say what data goes into the cryptogram

Amount: 1.00, currency 978 (EUR). Country code 0246. Unpredictable number the terminal gave the card: 12345678.
(Note: the note at the top of the tap capture file lists currency 0246 and
country 0978 — the raw bytes say the opposite, 9F1A country 0246 and 5F2A
currency 0978, and the bytes are what counts.)
Every card answer ended `90 00` — understood, done.

## The 0200 (the terminal's message to the bank)

This is what a real terminal would send after that tap. MTI 0200, financial request.
Fields: card number (DE2), purchase (DE3), amount 1.00 (DE4), date/time (DE7),
trace number 000001 (DE11), expiry 2912 (DE14), contactless chip entry (DE22),
track 2 (DE35), test terminal TEST0001 (DE41), test merchant TESTMERCHANT001 (DE42),
currency 978 (DE49), and the chip data (DE55 — the cryptogram and the EMV tags).

```
303230307224040020C082003136313233343536303031323334353630383030
3030303030303030303030303031303031303034313030303030303030303031
323931323037313238313233343536303031323334353630383D323931323232
30303939395445535430303031544553544D45524348414E5430303139373830
39309F26085F12155BFDCB5D339F2701809F360200F19F100706011203A01002
9F3704123456789F02060000000001009F03060000000000009F1A0202469505
00000000005F2A0209789A032610049C0100820220009F34031F0302
```

DE55, the chip data (14 tags: 9F26 cryptogram, 9F27, 9F36 ATC, 9F10, 9F37,
9F02, 9F03, 9F1A, 95, 5F2A, 9A, 9C, 82, 9F34):

```
9F26085F12155BFDCB5D339F2701809F360200F19F100706011203A010029F37
04123456789F02060000000001009F03060000000000009F1A02024695050000
0000005F2A0209789A032610049C0100820220009F34031F0302
```

## The checks (this is what makes it "verified")

- **Round-trip, 0200 — PASS.** The built 0200 was decoded
  back, and every field came back exactly as built. The message is well formed.
- **Cryptogram check — PASS.** The card's cryptogram
  (9F26 `5F12155BFDCB5D33`) was recomputed from scratch: the card's test master key
  from the simulator's own setup file (MK_AC `0123456789ABCDEFFEDCBA9876543210`),
  the session key made from the transaction counter, and the Retail MAC over the
  transaction data, AIP, ATC and Issuer Application Data. The recomputed value
  matches the card's value byte for byte. The cryptogram is real, not just copied.
- **DE55 tag check — PASS.** All 14 tags in DE55 parse
  cleanly, and every value matches the tap data (cryptogram, ATC, amounts, dates,
  unpredictable number). Nothing was invented.
- **0210 response — PASS.** The bank's answer decodes
  to approved (response code 00), echoes the date/time and trace number, and
  carries test retrieval reference 100410000001 and authorisation code 123456.

## The 0210 (the bank's answer)

```
30323130022000000E0000003130303431303030303030303030303131303034
31303030303030313132333435363030
```

## What this proves for Ghost Tap's reader

One line: the tap data Ghost Tap reads is enough to build a real authorisation
message, and the cryptogram in it checks out — the reading, the understanding
of the card's answers, and the message the terminal builds from them are all
correct, end to end, from tap to approved.

Files in this folder: build_simulation.py (the driver), message-0200.hex,
message-0210.hex, de55.hex, simulation.json (all values + check results).
