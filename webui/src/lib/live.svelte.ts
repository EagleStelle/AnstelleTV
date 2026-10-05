// Live updates from the TV. The server pushes the names of database tables that changed;
// pages subscribe to the tables they show and refetch. Reconnects with backoff.

type Listener = (tables: string[]) => void;

interface SocketMessage {
	type: 'hello' | 'invalidate';
	tables?: string[];
}

class LiveUpdates {
	connected = $state(false);

	private socket: WebSocket | null = null;
	private listeners = new Set<{ tables: Set<string>; listener: Listener }>();
	private retryMs = 1000;
	private timer: ReturnType<typeof setTimeout> | null = null;
	private stopped = true;

	start(): void {
		if (!this.stopped) return;
		this.stopped = false;
		this.connect();
	}

	stop(): void {
		this.stopped = true;
		if (this.timer) clearTimeout(this.timer);
		this.socket?.close();
		this.socket = null;
		this.connected = false;
	}

	/** Calls [listener] when any of [tables] changes. Returns an unsubscribe function. */
	subscribe(tables: string[], listener: Listener): () => void {
		const entry = { tables: new Set(tables), listener };
		this.listeners.add(entry);
		return () => this.listeners.delete(entry);
	}

	private connect(): void {
		const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
		const socket = new WebSocket(`${protocol}//${location.host}/ws`);
		this.socket = socket;

		socket.onopen = () => {
			this.connected = true;
			this.retryMs = 1000;
		};
		socket.onmessage = (event) => {
			const message = JSON.parse(event.data as string) as SocketMessage;
			if (message.type !== 'invalidate' || !message.tables) return;
			for (const { tables, listener } of this.listeners) {
				if (message.tables.some((table) => tables.has(table))) listener(message.tables);
			}
		};
		socket.onclose = () => {
			this.connected = false;
			this.socket = null;
			if (this.stopped) return;
			this.timer = setTimeout(() => this.connect(), this.retryMs);
			this.retryMs = Math.min(this.retryMs * 2, 15000);
		};
	}
}

export const live = new LiveUpdates();
