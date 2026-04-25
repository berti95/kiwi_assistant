"""Wire protocol shared between the tablet and the backend.

Messages are JSON strings sent over the WebSocket. Both directions use a
``type`` discriminator. The full V1 catalogue:

Client → server
    session.start    {"type": "session.start", "api_key": "kwi_..."}
    audio.input      {"type": "audio.input",  "data": "<base64 PCM 16k>"}
    audio.end        {"type": "audio.end"}
    session.end      {"type": "session.end"}

Server → client
    session.ready    {"type": "session.ready"}
    audio.output     {"type": "audio.output", "data": "<base64 PCM 24k>"}
    transcript.input {"type": "transcript.input",  "text": "..."}
    transcript.output{"type": "transcript.output", "text": "..."}
    response.end     {"type": "response.end"}
    error            {"type": "error", "message": "..."}

WebSocket close codes used for auth/protocol errors:
    4001  expected session.start as the first message
    4002  malformed JSON or missing required field
    4003  invalid api_key
"""

# Client → server message types.
TYPE_SESSION_START = "session.start"
TYPE_AUDIO_INPUT = "audio.input"
TYPE_AUDIO_END = "audio.end"
TYPE_SESSION_END = "session.end"

# Server → client message types.
TYPE_SESSION_READY = "session.ready"
TYPE_AUDIO_OUTPUT = "audio.output"
TYPE_TRANSCRIPT_INPUT = "transcript.input"
TYPE_TRANSCRIPT_OUTPUT = "transcript.output"
TYPE_RESPONSE_END = "response.end"
TYPE_ERROR = "error"

# Close codes.
CLOSE_EXPECTED_SESSION_START = 4001
CLOSE_BAD_REQUEST = 4002
CLOSE_INVALID_API_KEY = 4003
