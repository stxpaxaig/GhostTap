# EMV Authorization Simulator Integration

Status: integration contract discovery in progress. This document records only verified vendor facts and the implementation boundary; it deliberately does not invent endpoint paths, JSON property names, authentication, response codes, or service startup commands.

## Verified vendor contract

Merchant Testcards describes the simulator as a REST API server. A test system POSTs:
- PAN
- PAN sequence number
- EMV data encoded as a TLV string (typically ISO 8583 DE55)

The service validates the card cryptography and returns response EMV data as a TLV string. The package includes a test client and documentation. It may run in a console window or as a service.

Vendor warning: do not use the simulator with live production bank cards.

Reference: https://merchant-testcards.com/pages/free-emv-authorization-simulator

## Existing Ghost Tap boundary (baseline 73a642534d7be6034b9de6916371b7fc0538834b)

- `EmvEngine` reads a real tapped card over Android `IsoDep`.
- `EmvEngine.runDemoCard()` runs the built-in recorded test fixture through the same APDU flow.
- The current engine builds an ISO 8583 0200 message locally and explicitly does not send it.
- The built-in demo host approval is hardcoded fixture data; it is not a live authorization response.
- The NFC screen currently exposes text-oriented payment-card read and demo actions.

## Integration architecture

1. Keep live-card reading and the existing demo fixture intact.
2. Add a separate `EmvAuthorizationSimulatorClient` behind a test-only gateway. It must never be called by the live-card read path.
3. Use only the vendor's documented endpoint, request encoding, response encoding, and service startup procedure after the downloadable package documentation is available.
4. Build the test request from a synthetic/test card fixture only. Reject live-card data at the UI and gateway boundary.
5. Parse the returned response TLV with Ghost Tap's existing BER-TLV parser, display tags and validation outcome, and preserve the raw response for diagnostics.
6. Label the result as a simulator response, never as a real issuer/bank approval.
7. Keep the simulator endpoint local/configurable; do not hardcode a vendor host or assume a port.
8. Add unit tests for request encoding, response parsing, malformed TLV, timeout, connection refusal, and explicit test-only enforcement.

## UI target

The NFC area should present an EMV Lab dashboard rather than terminal-style text controls:
- Card-like header with EMV chip/NFC visual and live status.
- Separate actions for **Read a card** and **Run test fixture**.
- Simulator connection panel showing **Not configured**, **Connected**, or **Unavailable** from actual health checks only.
- A test transaction timeline: Card fixture → Request TLV → Simulator validation → Response TLV.
- Result panel with response tags, elapsed time, and an unmistakable **SIMULATOR / TEST DATA** label.
- Raw APDU/TLV and request/response details behind an expandable diagnostics area.
- No green “approved” state unless it came from a successfully parsed simulator response; fixture approvals remain clearly identified as fixture data.

## External dependency

The vendor's download form sends the executable and documentation by email and asks for an email address plus acceptance of promotional email. The actual package and API documentation have not yet been obtained or inspected. Implementation must not proceed by guessing its wire contract.
