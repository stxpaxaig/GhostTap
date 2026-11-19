# Ghost Tap

**Ghost Tap is an executable Android device-intelligence and hardware-interaction platform.** It brings embedded firmware execution, physical board access, NFC/EMV protocol tooling, network utilities, native Android interfaces, integrated command execution, and an AI-driven capability/orchestration architecture into one extensible environment.

This is a working engineering project, not a hardware-control mockup or a chatbot concept. Capabilities have different verification states; the application distinguishes registered operations from functions that have not yet been exercised on physical hardware.

## What Ghost Tap does

### Embedded systems: virtual and physical

- Integrates with **VirtualESP32**, a separate Android companion that runs an Xtensa-based ESP32 firmware-emulation environment.
- VirtualESP32 loads and executes ESP32 firmware; this is firmware execution, not simulated button behavior or mocked GPIO responses.
- Supports an embedded software environment that includes Python and SPIFFS-related filesystem functionality.
- Ghost Tap communicates with VirtualESP32. Owner-tested demonstrations include bidirectional PING/PONG communication and GPIO4 switching.
- Provides physical USB/UART interaction pathways for communicating with development boards and flashing firmware.
- Brings virtual firmware experimentation and physical-board workflows into the same broader device-interaction platform.

### NFC and EMV protocol tooling

- Reads supported physical contactless cards through native Android NFC interfaces.
- Issues and processes NFC/APDU commands and exposes protocol-level information.
- Includes EMV-oriented protocol inspection and a dedicated EMV Lab workspace.
- Separates live card interaction from local synthetic fixtures used for repeatable testing.
- Protocol inspection and local test fixtures are not represented as live issuer authorization or settlement.

### Network and connectivity

- Network discovery and diagnostic tools.
- Device connectivity and investigation workflows.
- Bluetooth discovery and device interaction.
- Cross-subsystem workflows that can combine device observations with registered operations.

### Visual and system intelligence

- Visual Intelligence workspace for camera-based observation and device investigation.
- Integrated terminal/Linux-oriented command execution as part of Ghost Tap's own application architecture; a separately installed Termux app is not required.
- Native Android subsystems and an extensible operation registry.
- Capability Atlas for inspecting registered functions, inputs, outputs, prerequisites, source methods, and verification state.

## VNIC: intelligence connected to execution

Ghost Tap's VNIC architecture is designed to connect model reasoning to the application's actual operational capabilities.

Its intended operating cycle is:

**OBSERVE → INTERPRET → RESEARCH → PLAN → ACT → EVALUATE → LEARN → EXTEND → CONTINUE**

VNIC works with Ghost Tap's registered capability infrastructure. It is designed to interpret a task, identify relevant native operations, gather technical context, form a plan, invoke available execution paths, inspect outcomes, and retain useful operational knowledge.

This is an execution-oriented architecture. The objective is not merely to generate advice or commands for a person to copy into another tool; it is to coordinate reasoning with the software and hardware interfaces available inside Ghost Tap.

The capability registry exposes operation metadata such as identifiers, categories, inputs, outputs, prerequisites, effects, verification state, and native source methods. This provides a common integration surface for the intelligence layer and the user interface.

## Project direction

Ghost Tap is intended to be hardware- and protocol-extensible rather than tied to one board, manufacturer, or device family. Its development spans Android engineering, embedded systems, firmware execution, USB/UART, NFC/EMV, Bluetooth, networking, visual investigation, command execution, and AI-assisted orchestration.

The long-term goal is a unified environment for investigating and interacting with heterogeneous devices and systems, while preserving access to the underlying technical controls and diagnostics.

## Build and development

- Android application built with Kotlin and Jetpack Compose.
- Android SDK / Gradle build.
- Automated GitHub Actions workflow builds the debug APK and runs JVM unit tests.
- Active experimental development takes place on `ghosttap/emv-authorization-ui-v1`.
- The established baseline on `main` is kept separate from experimental development.

To build locally:

```bash
./gradlew :app:assembleDebug
```

To run JVM unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

## Development status and verification

Ghost Tap is actively developed by its primary independent maintainer. The public repository contains the Android application source and ongoing engineering work. Physical hardware demonstrations and virtual-device demonstrations are distinguished from automated build results; a successful APK build does not, by itself, establish that every hardware pathway has been tested.

The experimental branch is used to validate new interface, capability-registry, VNIC, and build/test improvements without replacing the protected baseline.

## Open-source collaboration

The project is relevant to Android developers, embedded and firmware engineers, hardware-interoperability developers, protocol researchers, and contributors exploring the connection between AI reasoning and real device execution.

Repository: https://github.com/stxpaxaig/GhostTap
