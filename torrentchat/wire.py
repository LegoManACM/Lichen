"""Transport for requests to hubs and directly reachable nodes.

An address is either
  HOST:PORT               raw TCP, one JSON object per line
  ws://HOST[:PORT][/path] WebSocket, one JSON object per text message
  wss://...               WebSocket over TLS (e.g. a hub behind Cloudflare Tunnel or an HTTPS proxy)

Servers accept both on the same port: a connection that starts with an HTTP "GET" is upgraded to a
WebSocket, anything else is read as JSON lines. So a hub can be reached directly by IP and through a
proxy that only speaks HTTP/WebSocket at the same time.

Normal requests are one request + one reply per connection. Request types listed in `streams` (relay
attachments) keep the connection open and get the Conn to use as they like.
"""

import asyncio
import json
import ssl
from typing import Awaitable, Callable

from websockets.client import ClientProtocol
from websockets.frames import Frame, Opcode
from websockets.http11 import Request, Response
from websockets.protocol import State
from websockets.server import ServerProtocol
from websockets.uri import parse_uri

LINE_LIMIT = 4 * 1024 * 1024  # whole-phonebook replies can get big
CONNECT_TIMEOUT = 5.0


def is_url(addr: str) -> bool:
    return addr.startswith(("ws://", "wss://"))


def split_addr(addr: str) -> tuple[str, int]:
    """HOST:PORT -> (host, port). For ws(s) URLs, the host and port the URL points at."""
    if is_url(addr):
        uri = parse_uri(addr)
        return uri.host, uri.port
    host, port = addr.rsplit(":", 1)
    return host.strip("[]"), int(port)


class Conn:
    async def recv(self) -> dict | None: ...
    async def send(self, msg: dict) -> None: ...
    def close(self) -> None: ...


class LineConn(Conn):
    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        self.reader, self.writer = reader, writer

    async def recv(self) -> dict | None:
        while True:
            line = await self.reader.readline()
            if not line:
                return None
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if isinstance(msg, dict):
                return msg

    async def send(self, msg: dict) -> None:
        self.writer.write(json.dumps(msg).encode() + b"\n")
        await self.writer.drain()

    def close(self) -> None:
        self.writer.close()


class WsConn(Conn):
    """JSON messages over a WebSocket, driving the websockets library's sans-I/O protocol on our streams."""

    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, proto: ServerProtocol | ClientProtocol):
        self.reader, self.writer, self.proto = reader, writer, proto
        self._messages: list[dict] = []
        self._fragments: list[bytes] = []

    async def _flush(self) -> None:
        for data in self.proto.data_to_send():
            if data:
                self.writer.write(data)
        await self.writer.drain()

    async def recv(self) -> dict | None:
        while not self._messages:
            if self.proto.state is State.CLOSED:
                return None
            data = await self.reader.read(65536)
            if data:
                self.proto.receive_data(data)
            else:
                self.proto.receive_eof()
            for event in self.proto.events_received():
                if isinstance(event, Frame):
                    self._on_frame(event)
            await self._flush()  # pongs, close handshakes
            if not data and not self._messages:
                return None
        return self._messages.pop(0)

    def _on_frame(self, frame: Frame) -> None:
        if frame.opcode in (Opcode.TEXT, Opcode.BINARY, Opcode.CONT):
            self._fragments.append(frame.data)
            if frame.fin:
                payload, self._fragments = b"".join(self._fragments), []
                try:
                    msg = json.loads(payload)
                except ValueError:
                    return
                if isinstance(msg, dict):
                    self._messages.append(msg)

    async def send(self, msg: dict) -> None:
        self.proto.send_text(json.dumps(msg).encode())
        await self._flush()

    def close(self) -> None:
        try:
            if self.proto.state is State.OPEN:
                self.proto.send_close()
                for data in self.proto.data_to_send():
                    if data:
                        self.writer.write(data)
        except Exception:
            pass
        self.writer.close()


async def connect(addr: str) -> Conn:
    if not is_url(addr):
        host, port = split_addr(addr)
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port, limit=LINE_LIMIT), CONNECT_TIMEOUT)
        return LineConn(reader, writer)

    uri = parse_uri(addr)
    tls = ssl.create_default_context() if uri.secure else None
    reader, writer = await asyncio.wait_for(
        asyncio.open_connection(uri.host, uri.port, ssl=tls, server_hostname=uri.host if tls else None,
                                limit=LINE_LIMIT), CONNECT_TIMEOUT)
    proto = ClientProtocol(uri, max_size=LINE_LIMIT)
    proto.send_request(proto.connect())
    conn = WsConn(reader, writer, proto)
    try:
        await conn._flush()
        while True:
            data = await asyncio.wait_for(reader.read(65536), CONNECT_TIMEOUT)
            if not data:
                raise ConnectionError("connection closed during WebSocket handshake")
            proto.receive_data(data)
            for event in proto.events_received():
                if isinstance(event, Response):
                    if proto.state is not State.OPEN:
                        raise ConnectionError(f"WebSocket handshake failed: HTTP {event.status_code}")
                    return conn
                if isinstance(event, Frame):
                    conn._on_frame(event)
    except BaseException:
        writer.close()
        raise


async def request(addr: str, msg: dict, timeout: float = 3.0) -> dict | None:
    """Send msg to addr and return the reply, or None if the peer is unreachable or misbehaves."""
    try:
        conn = await asyncio.wait_for(connect(addr), timeout + CONNECT_TIMEOUT)
    except (OSError, ValueError, ConnectionError, asyncio.TimeoutError, Exception):
        return None
    try:
        await conn.send(msg)
        return await asyncio.wait_for(conn.recv(), timeout)
    except (OSError, ValueError, ConnectionError, asyncio.TimeoutError):
        return None
    finally:
        conn.close()


Handler = Callable[[dict], Awaitable[dict]]
StreamHandler = Callable[[dict, Conn], Awaitable[None]]


async def _accept(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> tuple[Conn, dict | None]:
    """Work out which protocol the client speaks; return the Conn and its first message."""
    first = await asyncio.wait_for(reader.readline(), 10)
    if not first.startswith(b"GET "):
        conn = LineConn(reader, writer)
        try:
            msg = json.loads(first)
        except ValueError:
            return conn, None
        return conn, msg if isinstance(msg, dict) else None

    proto = ServerProtocol(max_size=LINE_LIMIT)
    proto.receive_data(first)
    request_ = None
    while request_ is None:
        for event in proto.events_received():
            if isinstance(event, Request):
                request_ = event
        if request_ is None:
            data = await asyncio.wait_for(reader.read(65536), 10)
            if not data:
                return LineConn(reader, writer), None
            proto.receive_data(data)
    response = proto.accept(request_)
    proto.send_response(response)
    conn = WsConn(reader, writer, proto)
    await conn._flush()
    if proto.state is not State.OPEN:  # plain HTTP visitor, health check, ...
        return conn, None
    return conn, await asyncio.wait_for(conn.recv(), 10)


async def serve(handler: Handler, host: str, port: int,
                streams: dict[str, StreamHandler] | None = None) -> asyncio.Server:
    streams = streams or {}

    async def on_conn(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        conn: Conn | None = None
        try:
            conn, msg = await _accept(reader, writer)
            if msg is None:
                return
            if msg.get("type") in streams:
                await streams[msg["type"]](msg, conn)
                return
            await conn.send(await handler(msg))
        except (OSError, ValueError, ConnectionError, asyncio.TimeoutError, asyncio.IncompleteReadError):
            pass
        finally:
            if conn:
                conn.close()
            else:
                writer.close()

    return await asyncio.start_server(on_conn, host, port, limit=LINE_LIMIT)
