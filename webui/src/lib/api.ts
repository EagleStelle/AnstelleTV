// Typed client for the REST API served by the TV under /api.
// Authentication is an HttpOnly cookie set during pairing, so requests only need
// `credentials: 'same-origin'`. The pairing speaks for the owner or an admin profile.

export interface Health {
	app: string;
	version: string;
	deviceName: string;
	isTv: boolean;
}

export type Role = 'owner' | 'admin';

export interface Session {
	clientId: number;
	clientName: string;
	deviceName: string;
	version: string;
	isTv: boolean;
	profile: { id: number; name: string; role: Role };
}

export interface PairStart {
	requestId: string;
	expiresInSeconds: number;
	deviceName: string;
}

export interface Overview {
	servers: number;
	serverName?: string;
	serversNeedAttention: boolean;
	channels: number;
	customThemes: number;
	builtInThemes: number;
	pairedClients: number;
	backupSavedUtcMs?: number;
}

export interface Client {
	id: number;
	name: string;
	address?: string;
	userAgent?: string;
	lastSeenUtcMs: number;
	profileName?: string;
	you: boolean;
}

export interface Web {
	running: boolean;
	urls: string[];
	clients: Client[];
}

export interface Server {
	id: number;
	name: string;
	address: string;
	version?: string;
	userName?: string;
	isSignedIn: boolean;
	lastSyncUtcMs?: number;
	lastSyncError?: string;
	/** The running sync's progress line; absent while none runs. */
	progress?: string;
}

export interface Library {
	id: number;
	name: string;
	kind: string;
	enabled: boolean;
	canAir: boolean;
}

export interface ServerDetail {
	server: Server;
	counts: { type: string; count: number }[];
	libraries: Library[];
}

export interface FoundServer {
	signInId: string;
	name: string;
	address: string;
	version: string;
	quickConnect: boolean;
}

export interface SignInState {
	state: 'idle' | 'waiting' | 'signedIn' | 'failed';
	code?: string;
	serverId?: number;
	message?: string;
}

export interface Group {
	id: number;
	name: string;
	start: number;
	fromLibrary: boolean;
	channelCount: number;
}

export interface Channel {
	id: number;
	number: number;
	name: string;
	/** Where to load the channel's logo from; absent when it has none. */
	logoUrl?: string;
	isHidden: boolean;
	isFavorite: boolean;
	groupId: number;
	groupName: string;
	fromLibrary: boolean;
	seasonLabel?: string;
	inSeason: boolean;
}

export interface Playback {
	order: 'SHUFFLE' | 'EPISODE_ORDER' | 'BLOCKS';
	blockSize: number;
}

export interface PlaybackChoice {
	playback: Playback;
	label: string;
	hint: string;
}

export interface ChannelDetail {
	channel: Channel;
	series: Playback;
	movies: Playback;
	oncePerDay: boolean;
	airings: { startUtcMs: number; endUtcMs: number; title?: string }[];
	summary: string;
	airsSeries: boolean;
	airsMovies: boolean;
	fitsOncePerDay: boolean;
	seriesChoices: PlaybackChoice[];
	movieChoices: PlaybackChoice[];
}

export interface ChannelPatch {
	name?: string;
	groupId?: number;
	favorite?: boolean;
	visible?: boolean;
	series?: Playback;
	movies?: Playback;
	oncePerDay?: boolean;
}

export interface Rule {
	field: string;
	op: string;
	values: string[];
	/** How the builder reads it; filled in by the TV. */
	label?: string;
}

export interface RuleGroup {
	rules: Rule[];
	weight: number;
}

export interface Pick {
	kind: 'series' | 'movie';
	remoteId: string;
	weight: number;
	title?: string;
}

export interface Lineup {
	groups: RuleGroup[];
	includes: Pick[];
	excludes: Pick[];
}

export interface Reach {
	episodes: number;
	movies: number;
	videos: number;
	series: number;
	hours: number;
	episodeShare: number;
	movieShare: number;
	videoShare: number;
}

export interface LineupPreview {
	lineup: Lineup;
	reach: Reach;
}

export interface Op {
	op: string;
	label: string;
	/** Typed rather than picked: keywords and "contains". */
	typed: boolean;
	/** Answered by one pick (two for "between"). */
	single: boolean;
}

export interface Field {
	field: string;
	label: string;
	hint: string;
	ops: Op[];
}

export interface Builder {
	fields: Field[];
	weights: { weight: number; label: string; hint: string }[];
}

export interface Option {
	value: string;
	label: string;
	count: number;
}

export interface Theme {
	id: number;
	name: string;
	isBuiltIn: boolean;
	swatches: string[];
	/** The theme the paired profile wears, and so this browser. */
	inUse: boolean;
}

export interface ThemeSpec {
	key: string;
	name: string;
	version: number;
	colors: Record<string, string | string[]>;
	typography: { display: string; body: string; scale: number };
	guide: { density: string; cellRadiusDp: number };
	effects: { scanlines: boolean; scanlineAlpha: number };
}

export interface Readability {
	foreground: string;
	background: string;
	ok: boolean;
	text: string;
	inForeground: string;
	inBackground: string;
}

export interface ThemeDesign {
	id: number;
	isBuiltIn: boolean;
	spec: ThemeSpec;
	roles: { key: string; label: string; about: string; hex: string }[];
	readability: Readability[];
	choices: {
		fonts: { value: string; label: string }[];
		textSizes: { value: number; label: string }[];
		densities: { value: string; label: string }[];
		radii: { value: number; label: string }[];
		scanlines: { value: number; label: string }[];
	};
}

export interface Backup {
	profiles: number;
	channels: number;
	groups: number;
	themes: number;
	servers: number;
	historyDays: number;
	savedUtcMs?: number;
	canRestore: boolean;
}

export class ApiError extends Error {
	constructor(
		readonly status: number,
		readonly code: string,
		message: string
	) {
		super(message);
	}
}

async function send(method: string, path: string, body?: BodyInit, type?: string): Promise<Response> {
	const response = await fetch(`/api${path}`, {
		method,
		credentials: 'same-origin',
		headers: type ? { 'Content-Type': type } : undefined,
		body
	});
	if (!response.ok) {
		let code = `http_${response.status}`;
		let message = response.statusText || 'That didn’t work.';
		try {
			const error = (await response.json()) as { error: string; message: string };
			code = error.error;
			message = error.message;
		} catch {
			// Not a JSON error body; keep the HTTP status text.
		}
		throw new ApiError(response.status, code, message);
	}
	return response;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
	const response = await send(method, path, body === undefined ? undefined : JSON.stringify(body), body === undefined ? undefined : 'application/json');
	if (response.status === 204 || response.status === 202) return undefined as T;
	return (await response.json()) as T;
}

/** Sends a file as it is (a logo, a theme, a backup). */
async function upload<T>(method: string, path: string, file: Blob): Promise<T> {
	const response = await send(method, path, file, file.type || 'application/octet-stream');
	if (response.status === 204 || response.status === 202) return undefined as T;
	return (await response.json()) as T;
}

const get = <T>(path: string) => request<T>('GET', path);

export const api = {
	health: () => get<Health>('/health'),
	session: () => get<Session>('/session'),
	logout: () => request<void>('DELETE', '/session'),
	startPairing: (clientName: string) => request<PairStart>('POST', '/pair/start', { clientName }),
	confirmPairing: (requestId: string, pin: string) =>
		request<{ clientId: number; clientName: string }>('POST', '/pair/confirm', { requestId, pin }),

	overview: () => get<Overview>('/overview'),
	web: () => get<Web>('/web'),
	unpair: (clientId: number) => request<void>('DELETE', `/clients/${clientId}`),

	servers: () => get<Server[]>('/servers'),
	server: (id: number) => get<ServerDetail>(`/servers/${id}`),
	sync: (id: number, full: boolean) => request<void>('POST', `/servers/${id}/sync`, { full }),
	setLibrary: (serverId: number, libraryId: number, enabled: boolean) =>
		request<void>('PUT', `/servers/${serverId}/libraries/${libraryId}`, { enabled }),
	removeServer: (id: number) => request<void>('DELETE', `/servers/${id}`),
	findServer: (address: string) => request<FoundServer>('POST', '/servers/find', { address }),
	signIn: (signInId: string, username: string, password: string) =>
		request<{ id: number }>('POST', `/servers/signin/${signInId}`, { username, password }),
	quickConnect: (signInId: string) => request<{ code: string }>('POST', `/servers/signin/${signInId}/quickconnect`),
	signInState: (signInId: string) => get<SignInState>(`/servers/signin/${signInId}`),
	cancelSignIn: (signInId: string) => request<void>('DELETE', `/servers/signin/${signInId}`),

	channels: () => get<{ channels: Channel[]; bannerSize: number; bannerSizes: number[] }>('/channels'),
	channel: (id: number) => get<ChannelDetail>(`/channels/${id}`),
	updateChannel: (id: number, patch: ChannelPatch) => request<void>('PATCH', `/channels/${id}`, patch),
	deleteChannel: (id: number) => request<void>('DELETE', `/channels/${id}`),
	createChannel: (name: string, groupId: number, lineup: Lineup) =>
		request<{ id: number }>('POST', '/channels', { name, groupId, lineup }),
	setLogo: (id: number, image: Blob) => upload<void>('PUT', `/channels/${id}/logo`, image),
	clearLogo: (id: number) => request<void>('DELETE', `/channels/${id}/logo`),
	lineup: (id: number) => get<LineupPreview>(`/channels/${id}/lineup`),
	setLineup: (id: number, lineup: Lineup) => request<void>('PUT', `/channels/${id}/lineup`, lineup),
	preview: (lineup: Lineup) => request<LineupPreview>('POST', '/lineup/preview', lineup),
	builder: () => get<Builder>('/lineup/builder'),
	options: (field: string, op: string, query = '', from?: string) => {
		const params = new URLSearchParams({ field, op, q: query });
		if (from !== undefined) params.set('from', from);
		return get<Option[]>(`/lineup/options?${params}`);
	},
	setBannerSize: (value: number) => request<void>('PUT', '/settings/ondemand-banner', { value }),

	groups: () => get<{ groups: Group[]; nextStart: number; lastGroupId?: number }>('/groups'),
	createGroup: (name: string, start: number) => request<{ id: number }>('POST', '/groups', { name, start }),
	updateGroup: (id: number, change: { name?: string; start?: number }) => request<void>('PATCH', `/groups/${id}`, change),
	moveGroup: (id: number, delta: number) => request<void>('POST', `/groups/${id}/move`, { delta }),
	deleteGroup: (id: number) => request<void>('DELETE', `/groups/${id}`),

	themes: () => get<Theme[]>('/themes'),
	worn: () => get<{ id: number; spec: ThemeSpec }>('/theme'),
	useTheme: (themeId: number) => request<void>('PUT', '/theme', { themeId }),
	importTheme: (file: Blob) => upload<Theme>('POST', '/themes', file),
	newTheme: () => request<{ id: number }>('POST', '/themes/new'),
	copyTheme: (id: number) => request<{ id: number }>('POST', `/themes/${id}/copy`),
	deleteTheme: (id: number) => request<void>('DELETE', `/themes/${id}`),
	design: (id: number) => get<ThemeDesign>(`/themes/${id}/spec`),
	saveTheme: (id: number, spec: ThemeSpec) => request<ThemeDesign>('PUT', `/themes/${id}/spec`, { spec }),
	themeFileUrl: (id: number) => `/api/themes/${id}`,

	backup: () => get<Backup>('/backup'),
	backupUrl: '/api/backup/export',
	checkBackup: (file: Blob) => upload<{ question: string; explanation: string }>('POST', '/backup/check', file),
	restore: (file: Blob) => upload<void>('POST', '/backup/restore', file)
};

/** The message to show for a failed call. */
export function problem(error: unknown): string {
	return error instanceof ApiError ? error.message : 'Can’t reach the TV.';
}
