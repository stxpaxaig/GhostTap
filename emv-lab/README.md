# EMV lab — the test card and the test bank

This folder is the lab behind Ghost Tap's card reader. It is all test data.
Nothing here is real money and nothing here can pay at a real store.

## What's in here

- `test-card/` — the test card. A fake Visa card that lives in the card
  simulator: its number is 1234560012345608, it expires 31/12/2029, and its
  app is "VISA ELECTRON" (the simulator's own files spell it "VESA
  ELECTRON" — a typo in the simulator, not ours). The four setup files are
  the exact commands that build the card: the base setup, the contactless
  (tap) setup, and the PPSE/PSE lists the phone reads first.
  `known-good-contactless-exchange.txt` is one full tap, recorded
  command by command, that the reader was checked against.
- `test-bank/` — the test bank. The Merchant Testcards EMV Authorization
  Simulator (2022), a small Python server that acts as the card's bank: it
  checks the one-time code the card makes and answers approved or declined.
  It runs on port 8000. It was patched in 4 places so it knows our test
  card: the card's number was added to the Visa routing in two files, and
  its test key was added as the card's key in two more places. It only
  knows this one test card — it cannot check any real card.
- `payment-sim/` — the proof the loop works end to end. A Python script
  that replays the recorded tap, works out the one-time code from the test
  key, builds the payment message (0200), and checks the test bank's
  approved answer (0210). The one-time code matches byte for byte.

## The important limits, in plain words

- The test card is Monopoly money. Fake number, no bank behind it, no
  money on it. A store would say no.
- The test bank is not a real bank. It only knows the test card's test key.
  A real card's code can only be checked by its real bank.
- The real reader lives in the Ghost Tap app source (the `core/Emv*.kt`
  files at the repo root). It reads a real card the same way it reads this
  test card — same code path — but on a real card it can only show the
  one-time code and check the card answered right and its counter moved.
  Only the card's real bank can fully prove the code.

## The test key and number

The test key `0123456789ABCDEFFEDCBA9876543210` and the test card number
above are public test data from the Merchant Testcards simulator. They are
not secrets and they cannot spend anything. Never put a real card's details
in here.
