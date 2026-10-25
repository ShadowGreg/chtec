import asyncio, sys, time, email.utils, urllib.parse
import websockets
from websockets.http11 import Response
from websockets.datastructures import Headers
from edge_tts.drm import generate_sec_ms_gec_v2

SKEW = 3600  # «серверное» время на час впереди телефона
state = {"conn": 0}
log = open(sys.argv[2], "w")

def process_request(conn, request):
    state["conn"] += 1
    q = urllib.parse.parse_qs(urllib.parse.urlparse(request.path).query)
    expected = generate_sec_ms_gec_v2(time.time() + SKEW)
    ok = q["Sec-MS-GEC"][0] == expected
    log.write(f"conn{state['conn']} token_ok={ok} origin={request.headers.get('Origin')} cookie={request.headers.get('Cookie','')[:10]} ver={q['Sec-MS-GEC-Version'][0]}\n"); log.flush()
    if not ok:
        h = Headers(); h["Date"] = email.utils.formatdate(time.time() + SKEW, usegmt=True)
        return Response(403, "Forbidden", h, b"")
    return None

async def handler(ws):
    cfg = await ws.recv(); ssml = await ws.recv()
    log.write("CFG " + cfg.replace("\r\n", " | ") + "\n"); log.write("SSML " + ssml.replace("\r\n", " | ") + "\n"); log.flush()
    await ws.send("X-RequestId:1\r\nContent-Type:application/json\r\nPath:turn.start\r\n\r\n{}")
    meta = '{"Metadata":[{"Type":"WordBoundary","Data":{"Offset":1000000,"Duration":4000000,"text":{"Text":"Привет","Length":6,"BoundaryType":"WordBoundary"}}}]}'
    await ws.send("X-RequestId:1\r\nContent-Type:application/json\r\nPath:audio.metadata\r\n\r\n" + meta)
    meta2 = '{"Metadata":[{"Type":"WordBoundary","Data":{"Offset":6500000,"Duration":3000000,"text":{"Text":"&lt;\\u0442\\u0435\\u0441\\u0442&gt;","Length":6,"BoundaryType":"WordBoundary"}}}]}'
    await ws.send("X-RequestId:1\r\nContent-Type:application/json\r\nPath:audio.metadata\r\n\r\n" + meta2)
    audio = open(sys.argv[3], "rb").read()
    hdr = b"X-RequestId:1\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n"
    for i in range(0, len(audio), 4000):
        await ws.send(len(hdr).to_bytes(2, "big") + hdr + audio[i:i+4000])
    await ws.send("X-RequestId:1\r\nContent-Type:application/json\r\nPath:turn.end\r\n\r\n{}")
    await asyncio.sleep(0.2)

async def main():
    async with websockets.serve(handler, "127.0.0.1", int(sys.argv[1]), process_request=process_request, max_size=None):
        await asyncio.sleep(30)
asyncio.run(main())
