import XCTest
@testable import GooglyMac

/// Opt-in live check of what Bluey does with held speech: start focus, use a tool, or just answer.
/// Uses the real Realtime model, session instructions and tools with your saved OpenAI key, sending text
/// instead of audio (the testHeldAudio… probes send spoken audio like the phone). Costs a few cents. Run with:
///   BLUEY_LIVE_VOICE_PROBE=1 swift test --filter VoiceRoutingLiveTests
final class VoiceRoutingLiveTests: XCTestCase {
    /// The note the Android app adds after every held or typed request (Requests.NOTE in Requests.kt).
    static let phoneNote = "The preceding words were spoken (or typed) to Bluey. Do what they ask with your tools, or answer "
        + "them. Start a focus session only if they clearly say they are about to work on something themselves, or ask "
        + "to focus. Keep existing safety/personality instructions."

    /// The focus-only note the iPhone app (and Android builds before this branch) still send.
    static let legacyPhoneNote = "The preceding words were explicitly spoken while holding Bluey. When they describe work, "
        + "call focus_start with task and optional minutes (default 30). Never merely say you're ready. "
        + "For questions use the appropriate tool. Keep existing safety/personality instructions."

    @MainActor func testHeldSpeechStartsFocusOnlyForDeclaredTasks() async throws {
        guard ProcessInfo.processInfo.environment["BLUEY_LIVE_VOICE_PROBE"] == "1" else {
            throw XCTSkip("Live probe: set BLUEY_LIVE_VOICE_PROBE=1 to run it.")
        }
        let cases: [(words: String, startsFocus: Bool)] = [
            ("Hello, can you hear me?", false),
            ("Open Notes and type a shopping list: milk, eggs, bread.", false),
            ("What's the weather in London right now?", false),
            ("What's this on my screen?", false),
            ("I'm writing my quarterly report for 30 minutes.", true),
            ("I'm going to work on my pitch deck now.", true),
        ]
        var wrong: [String] = []
        for c in cases {
            let (tools, text) = try await Self.firstResponse(to: c.words)
            let started = tools.contains("focus_start")
            print("PROBE \"\(c.words)\" -> tools: \(tools.isEmpty ? "none" : tools.joined(separator: ", ")); reply: \(text)")
            if started != c.startsFocus { wrong.append("\"\(c.words)\" -> \(tools)") }
        }
        XCTAssert(wrong.isEmpty, "Wrong focus routing: \(wrong.joined(separator: "; "))")
    }

    /// Real sessions are long: earlier turns (and a running focus session) sit in context. A request made
    /// later in the same session must still be done, not turned into a focus task.
    @MainActor func testRequestsLaterInASessionAreNotTurnedIntoFocusTasks() async throws {
        guard ProcessInfo.processInfo.environment["BLUEY_LIVE_VOICE_PROBE"] == "1" else {
            throw XCTSkip("Live probe: set BLUEY_LIVE_VOICE_PROBE=1 to run it.")
        }
        let request = "Open Notes and type hello from Bluey."
        let scenarios: [(name: String, before: [String], extra: String?)] = [
            ("after a focus task", ["I'm testing the workflow for 10 minutes."], nil),
            ("after a greeting", ["Hello, can you hear me?"], nil),
            ("while focus runs", [], "Current focus task: Testing workflow. State: running."),
        ]
        var wrong: [String] = []
        for s in scenarios {
            let turns = try await Self.conversation(s.before + [request], extra: s.extra)
            let last = turns.last ?? []
            print("PROBE [\(s.name)] turns: \(turns.map { $0.isEmpty ? "none" : $0.joined(separator: "+") })")
            if last.contains("focus_start") { wrong.append(s.name) }
        }
        XCTAssert(wrong.isEmpty, "Request became a focus task: \(wrong.joined(separator: ", "))")
    }

    // MARK: Held audio, as the phone sends it

    static let heldSpeech: [(words: String, startsFocus: Bool)] = [
        ("Open Notes and type hello from Bluey.", false),
        ("Hello, can you hear me?", false),
        ("What's on my screen?", false),
        ("I'm writing my report for 30 minutes.", true),
    ]

    /// The phone's real path, with spoken audio (macOS `say`) instead of text: the held audio is appended and
    /// committed, and only once its transcription arrives does the transcript go in as user text, then the
    /// phone's note, then a text-only response.create. Each utterance gets a fresh session, run twice
    /// (BLUEY_LIVE_VOICE_RUNS to change). Speech files go in BLUEY_LIVE_VOICE_AUDIO_DIR (default: a temp folder).
    @MainActor func testHeldAudioStartsFocusOnlyForDeclaredWork() async throws {
        try await Self.checkHeldAudio(note: Self.phoneNote, label: "phone note")
    }

    /// Same as above with the iPhone app's older focus-only note, which the Mac's instructions must also handle.
    @MainActor func testHeldAudioWithLegacyNoteStartsFocusOnlyForDeclaredWork() async throws {
        try await Self.checkHeldAudio(note: Self.legacyPhoneNote, label: "legacy note")
    }

    @MainActor static func checkHeldAudio(note: String, label: String) async throws {
        let env = ProcessInfo.processInfo.environment
        guard env["BLUEY_LIVE_VOICE_PROBE"] == "1" else {
            throw XCTSkip("Live probe: set BLUEY_LIVE_VOICE_PROBE=1 to run it.")
        }
        let runs = Int(env["BLUEY_LIVE_VOICE_RUNS"] ?? "") ?? 2
        let folder = URL(fileURLWithPath: env["BLUEY_LIVE_VOICE_AUDIO_DIR"]
            ?? (NSTemporaryDirectory() as NSString).appendingPathComponent("bluey-voice-probe"))
        var wrong: [String] = []
        for c in heldSpeech {
            let pcm = try speech(c.words, in: folder)
            for run in 1...runs {
                let tag = "PROBE-AUDIO [\(label)] \"\(c.words)\" run \(run)"
                do {
                    let turn = try await heldAudioTurn(pcm, note: note)
                    let tools = turn.calls.isEmpty ? "none" : turn.calls.joined(separator: ", ")
                    print("\(tag) heard: \"\(turn.heard)\" -> tools: \(tools); reply: \(turn.reply)")
                    if turn.tools.contains("focus_start") != c.startsFocus { wrong.append("\"\(c.words)\" -> \(turn.tools)") }
                } catch {
                    print("\(tag) ERROR: \(error.localizedDescription)")
                    wrong.append("\"\(c.words)\" -> error: \(error.localizedDescription)")
                }
            }
        }
        XCTAssert(wrong.isEmpty, "Wrong focus routing with the \(label): \(wrong.joined(separator: "; "))")
    }

    /// Speaks `words` with macOS `say` and returns it as 24 kHz mono 16-bit little-endian PCM (what the phone's
    /// mic sends), with a quarter second of silence either side as when holding Bluey.
    static func speech(_ words: String, in folder: URL) throws -> Data {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let name = String(words.lowercased().map { $0.isLetter || $0.isNumber ? $0 : "-" })
        let aiff = folder.appendingPathComponent(name + ".aiff"), wav = folder.appendingPathComponent(name + ".wav")
        func run(_ tool: String, _ arguments: [String]) throws {
            let process = Process()
            process.executableURL = URL(fileURLWithPath: tool)
            process.arguments = arguments
            try process.run()
            process.waitUntilExit()
            guard process.terminationStatus == 0 else {
                throw NSError(domain: "VoiceProbe", code: 3,
                              userInfo: [NSLocalizedDescriptionKey: "\(tool) failed (\(process.terminationStatus))"])
            }
        }
        try run("/usr/bin/say", ["-o", aiff.path, words])
        try run("/usr/bin/afconvert", ["-f", "WAVE", "-d", "LEI16@24000", "-c", "1", aiff.path, wav.path])
        // Walk the RIFF chunks to "data": afconvert adds others (like FLLR), so the header isn't 44 bytes.
        let bytes = [UInt8](try Data(contentsOf: wav))
        var at = 12
        while at + 8 <= bytes.count {
            let id = String(decoding: bytes[at..<at + 4], as: UTF8.self)
            let size = Int(bytes[at + 4]) | Int(bytes[at + 5]) << 8 | Int(bytes[at + 6]) << 16 | Int(bytes[at + 7]) << 24
            if id == "data" {
                let silence = Data(count: 24000 / 4 * 2)
                return silence + Data(bytes[(at + 8)..<min(bytes.count, at + 8 + size)]) + silence
            }
            at += 8 + size + (size & 1)
        }
        throw NSError(domain: "VoiceProbe", code: 4, userInfo: [NSLocalizedDescriptionKey: "No data chunk in \(wav.path)"])
    }

    struct HeldTurn {
        var heard = ""
        /// Tool names called, in order, across the follow-up rounds.
        var tools: [String] = []
        /// The same calls with their arguments, for printing.
        var calls: [String] = []
        var reply = ""
    }

    /// Plays one held utterance exactly as LiveVoice.kt does, in a fresh session, answering tool calls with
    /// plausible results like `conversation(_:extra:)`.
    @MainActor static func heldAudioTurn(_ pcm: Data, note: String) async throws -> HeldTurn {
        let token = try await RealtimeHost.mintToken()
        var request = URLRequest(url: URL(string: "wss://api.openai.com/v1/realtime?model=\(RealtimeHost.model)")!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let socket = URLSession.shared.webSocketTask(with: request)
        socket.resume()
        defer { socket.cancel(with: .normalClosure, reason: nil) }
        func send(_ event: [String: Any]) async throws {
            let data = try JSONSerialization.data(withJSONObject: event)
            try await socket.send(.string(String(decoding: data, as: UTF8.self)))
        }
        func message(_ role: String, _ text: String) -> [String: Any] {
            ["type": "message", "role": role, "content": [["type": "input_text", "text": text]]]
        }
        func fail(_ code: Int, _ text: String) -> NSError {
            NSError(domain: "VoiceProbe", code: code, userInfo: [NSLocalizedDescriptionKey: text])
        }
        let deadline = Date().addingTimeInterval(90)
        func next() async throws -> [String: Any] {
            guard Date() < deadline else { throw fail(2, "Timed out") }
            let frame = try await socket.receive()
            var text = "{}"
            if case .string(let s) = frame { text = s }
            if case .data(let d) = frame { text = String(decoding: d, as: UTF8.self) }
            let event = (try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]) ?? [:]
            if event["type"] as? String == "error" {
                let error = event["error"] as? [String: Any]
                throw fail(1, error?["message"] as? String ?? "Realtime error")
            }
            return event
        }
        /// Waits for response.done; returns its function calls as (name, call_id, arguments) and its text.
        func awaitResponse() async throws -> ([(String, String, String)], String) {
            while true {
                let event = try await next()
                guard event["type"] as? String == "response.done" else { continue }
                let output = (event["response"] as? [String: Any])?["output"] as? [[String: Any]] ?? []
                let calls = output.filter { $0["type"] as? String == "function_call" }.map {
                    ($0["name"] as? String ?? "", $0["call_id"] as? String ?? "", $0["arguments"] as? String ?? "{}")
                }
                let text = output.flatMap { $0["content"] as? [[String: Any]] ?? [] }
                    .compactMap { $0["text"] as? String ?? $0["transcript"] as? String }.joined()
                return (calls, text)
            }
        }

        // session.created: clear the buffer, then append the held audio (~100 ms chunks) and commit it.
        while try await next()["type"] as? String != "session.created" {}
        try await send(["type": "input_audio_buffer.clear"])
        let chunk = 24000 / 10 * 2
        var at = 0
        while at < pcm.count {
            let end = min(pcm.count, at + chunk)
            try await send(["type": "input_audio_buffer.append", "audio": pcm.subdata(in: at..<end).base64EncodedString()])
            at = end
        }
        try await send(["type": "input_audio_buffer.commit"])

        // Wait for this item's transcription, as the phone does before asking for a response.
        var item: String?
        var turn = HeldTurn()
        transcribing: while true {
            let event = try await next()
            switch event["type"] as? String {
            case "input_audio_buffer.committed":
                item = event["item_id"] as? String
            case "conversation.item.input_audio_transcription.completed"
                where item == nil || event["item_id"] as? String == item:
                turn.heard = (event["transcript"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                break transcribing
            case "conversation.item.input_audio_transcription.failed":
                throw fail(5, "Transcription failed")
            default: continue
            }
        }
        guard !turn.heard.isEmpty else { return turn } // the phone stops here too

        try await send(["type": "conversation.item.create", "item": message("user", turn.heard)])
        try await send(["type": "conversation.item.create", "item": message("system", note)])
        try await send(["type": "response.create", "response": ["output_modalities": ["text"]]])
        var (calls, text) = try await awaitResponse()
        var replies = [text]
        var rounds = 0
        while !calls.isEmpty && rounds < 3 {
            rounds += 1
            for (name, id, arguments) in calls {
                turn.tools.append(name)
                turn.calls.append(name + arguments.replacingOccurrences(of: "\n", with: " "))
                let result = name == "focus_start" ? "Focus started for 30 minutes."
                    : name == "look_at_screen" ? "Frontmost app: Finder. No text." : "Done."
                try await send(["type": "conversation.item.create",
                                "item": ["type": "function_call_output", "call_id": id, "output": result]])
            }
            try await send(["type": "response.create"])
            (calls, text) = try await awaitResponse()
            replies.append(text)
        }
        turn.reply = replies.filter { !$0.isEmpty }.joined(separator: " | ")
        return turn
    }

    /// Plays held utterances (as text) in one session, answering tool calls with plausible results.
    /// Returns the tools called on each turn.
    @MainActor static func conversation(_ utterances: [String], extra: String?) async throws -> [[String]] {
        let token = try await RealtimeHost.mintToken(extra: extra)
        var request = URLRequest(url: URL(string: "wss://api.openai.com/v1/realtime?model=\(RealtimeHost.model)")!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let socket = URLSession.shared.webSocketTask(with: request)
        socket.resume()
        defer { socket.cancel(with: .normalClosure, reason: nil) }
        func send(_ event: [String: Any]) async throws {
            let data = try JSONSerialization.data(withJSONObject: event)
            try await socket.send(.string(String(decoding: data, as: UTF8.self)))
        }
        func message(_ role: String, _ text: String) -> [String: Any] {
            ["type": "message", "role": role, "content": [["type": "input_text", "text": text]]]
        }
        func next() async throws -> [String: Any] {
            let frame = try await socket.receive()
            var text = "{}"
            if case .string(let s) = frame { text = s }
            if case .data(let d) = frame { text = String(decoding: d, as: UTF8.self) }
            return (try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]) ?? [:]
        }
        /// Waits for response.done; returns its function calls as (name, call_id, arguments).
        func awaitResponse() async throws -> [(String, String, String)] {
            let deadline = Date().addingTimeInterval(45)
            while Date() < deadline {
                let event = try await next()
                if event["type"] as? String == "error" {
                    let error = event["error"] as? [String: Any]
                    throw NSError(domain: "VoiceProbe", code: 1,
                                  userInfo: [NSLocalizedDescriptionKey: error?["message"] as? String ?? "Realtime error"])
                }
                guard event["type"] as? String == "response.done" else { continue }
                let output = (event["response"] as? [String: Any])?["output"] as? [[String: Any]] ?? []
                return output.filter { $0["type"] as? String == "function_call" }.map {
                    ($0["name"] as? String ?? "", $0["call_id"] as? String ?? "", $0["arguments"] as? String ?? "{}")
                }
            }
            throw NSError(domain: "VoiceProbe", code: 2, userInfo: [NSLocalizedDescriptionKey: "Timed out"])
        }
        while try await next()["type"] as? String != "session.created" {}
        var turns: [[String]] = []
        for words in utterances {
            try await send(["type": "conversation.item.create", "item": message("user", words)])
            try await send(["type": "conversation.item.create", "item": message("system", phoneNote)])
            try await send(["type": "response.create", "response": ["output_modalities": ["text"]]])
            var called: [String] = []
            var calls = try await awaitResponse()
            var rounds = 0
            while !calls.isEmpty && rounds < 3 {
                rounds += 1
                for (name, id, _) in calls {
                    called.append(name)
                    let result = name == "focus_start" ? "Focus started for 30 minutes." : name == "look_at_screen" ? "Frontmost app: Finder. No text." : "Done."
                    try await send(["type": "conversation.item.create",
                                    "item": ["type": "function_call_output", "call_id": id, "output": result]])
                }
                try await send(["type": "response.create"])
                calls = try await awaitResponse()
            }
            turns.append(called)
        }
        return turns
    }

    /// Sends one held utterance (as text) to a fresh session; returns the tools called and any reply text.
    /// The phone trims old screen reads: it deletes a big tool output and puts a one-line stub with the same
    /// call_id right after the call. Checks Realtime accepts that (client ids, delete, previous_item_id) and keeps
    /// replying without errors.
    @MainActor func testTrimmedToolOutputsAreAccepted() async throws {
        guard ProcessInfo.processInfo.environment["BLUEY_LIVE_VOICE_PROBE"] == "1" else {
            throw XCTSkip("Live probe: set BLUEY_LIVE_VOICE_PROBE=1 to run it.")
        }
        let token = try await RealtimeHost.mintToken()
        var request = URLRequest(url: URL(string: "wss://api.openai.com/v1/realtime?model=\(RealtimeHost.model)")!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let socket = URLSession.shared.webSocketTask(with: request)
        socket.resume()
        defer { socket.cancel(with: .normalClosure, reason: nil) }
        func send(_ event: [String: Any]) async throws {
            try await socket.send(.string(String(decoding: try JSONSerialization.data(withJSONObject: event), as: UTF8.self)))
        }
        func next() async throws -> [String: Any] {
            var text = "{}"
            switch try await socket.receive() {
            case .string(let s): text = s
            case .data(let d): text = String(decoding: d, as: UTF8.self)
            @unknown default: break
            }
            return (try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]) ?? [:]
        }
        var errors: [String] = []
        /// Reads events until response.done; collects errors on the way.
        func untilDone() async throws -> [String: Any] {
            let deadline = Date().addingTimeInterval(45)
            while Date() < deadline {
                let event = try await next()
                if event["type"] as? String == "error" {
                    let e = event["error"] as? [String: Any]
                    errors.append("\(e?["code"] ?? "?"): \(e?["message"] ?? "") [\(e?["event_id"] ?? "")]")
                }
                if event["type"] as? String == "response.done" { return event["response"] as? [String: Any] ?? [:] }
            }
            throw NSError(domain: "VoiceProbe", code: 2, userInfo: [NSLocalizedDescriptionKey: "Timed out"])
        }
        func message(_ role: String, _ text: String) -> [String: Any] {
            ["type": "message", "role": role, "content": [["type": "input_text", "text": text]]]
        }
        while try await next()["type"] as? String != "session.created" {}

        // 1. A real tool call from the model.
        try await send(["type": "conversation.item.create", "item": message("user", "What's on my screen?")])
        try await send(["type": "response.create"])
        let first = try await untilDone()
        let call = (first["output"] as? [[String: Any]] ?? []).first { $0["type"] as? String == "function_call" }
        let callItem = try XCTUnwrap(call?["id"] as? String, "model didn't call a tool")
        let callID = try XCTUnwrap(call?["call_id"] as? String)

        // 2. A big output with our own id, plus a screenshot-like user message with our own id.
        let screen = "Frontmost app: Notes\n" + String(repeating: "L1 \"Some screen text\" @100,200\n", count: 120)
        try await send(["type": "conversation.item.create",
                        "item": ["id": "bprobeout1", "type": "function_call_output", "call_id": callID, "output": screen]])
        try await send(["type": "conversation.item.create", "item": ["id": "bprobeimg1"] .merging(message("user", "(screenshot)")) { a, _ in a }])
        try await send(["type": "response.create"])
        _ = try await untilDone()

        // 3. Trim: delete both, put a stub output right after the call, then keep talking.
        try await send(["type": "conversation.item.delete", "item_id": "bprobeout1", "event_id": "del_probe1"])
        try await send(["type": "conversation.item.delete", "item_id": "bprobeimg1", "event_id": "del_probe2"])
        try await send(["type": "conversation.item.create", "previous_item_id": callItem,
                        "item": ["id": "bprobestub1", "type": "function_call_output", "call_id": callID,
                                 "output": "Looked at the screen: Notes was open. (Details removed to save space.)"]])
        try await send(["type": "conversation.item.create", "item": message("user", "What did you just do? One short line.")])
        try await send(["type": "response.create"])
        let last = try await untilDone()
        print("PROBE trim: status=\(last["status"] ?? "?"), errors=\(errors)")
        XCTAssertEqual(last["status"] as? String, "completed")
        XCTAssert(errors.isEmpty, "Realtime errors: \(errors)")
    }

    @MainActor static func firstResponse(to words: String) async throws -> ([String], String) {
        let token = try await RealtimeHost.mintToken()
        var request = URLRequest(url: URL(string: "wss://api.openai.com/v1/realtime?model=\(RealtimeHost.model)")!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let socket = URLSession.shared.webSocketTask(with: request)
        socket.resume()
        defer { socket.cancel(with: .normalClosure, reason: nil) }

        func send(_ event: [String: Any]) async throws {
            let data = try JSONSerialization.data(withJSONObject: event)
            try await socket.send(.string(String(decoding: data, as: UTF8.self)))
        }
        func message(_ role: String, _ text: String) -> [String: Any] {
            ["type": "message", "role": role, "content": [["type": "input_text", "text": text]]]
        }
        func next() async throws -> [String: Any] {
            let frame = try await socket.receive()
            let text: String
            switch frame {
            case .string(let s): text = s
            case .data(let d): text = String(decoding: d, as: UTF8.self)
            @unknown default: text = "{}"
            }
            return (try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]) ?? [:]
        }

        let deadline = Date().addingTimeInterval(45)
        var sent = false
        while Date() < deadline {
            let event = try await next()
            switch event["type"] as? String {
            case "session.created" where !sent:
                sent = true
                try await send(["type": "conversation.item.create", "item": message("user", words)])
                try await send(["type": "conversation.item.create", "item": message("system", phoneNote)])
                try await send(["type": "response.create", "response": ["output_modalities": ["text"]]])
            case "error":
                let error = event["error"] as? [String: Any]
                throw NSError(domain: "VoiceProbe", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: error?["message"] as? String ?? "Realtime error"])
            case "response.done":
                let output = (event["response"] as? [String: Any])?["output"] as? [[String: Any]] ?? []
                let tools = output.filter { $0["type"] as? String == "function_call" }.compactMap { $0["name"] as? String }
                let text = output.flatMap { $0["content"] as? [[String: Any]] ?? [] }
                    .compactMap { $0["text"] as? String ?? $0["transcript"] as? String }.joined()
                return (tools, text)
            default: continue
            }
        }
        throw NSError(domain: "VoiceProbe", code: 2, userInfo: [NSLocalizedDescriptionKey: "Timed out"])
    }
}
