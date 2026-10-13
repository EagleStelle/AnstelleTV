// Words the pages share, the way the app's settings say them.

const relative = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
const clock = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });
const whole = new Intl.NumberFormat();

/** "just now", "5 minutes ago", "yesterday": how long ago [utcMs] was. */
export function ago(utcMs: number, now = Date.now()): string {
	const seconds = Math.round((utcMs - now) / 1000);
	if (Math.abs(seconds) < 60) return 'just now';
	const steps: [Intl.RelativeTimeFormatUnit, number][] = [
		['minute', 60],
		['hour', 3600],
		['day', 86400],
		['week', 604800],
		['month', 2629800],
		['year', 31557600]
	];
	let unit: Intl.RelativeTimeFormatUnit = 'minute';
	let size = 60;
	for (const [name, length] of steps) {
		if (Math.abs(seconds) >= length) {
			unit = name;
			size = length;
		}
	}
	return relative.format(Math.round(seconds / size), unit);
}

/** "8:24 PM": a time of day. */
export function time(utcMs: number): string {
	return clock.format(new Date(utcMs));
}

/** "1,234". */
export function number(value: number): string {
	return whole.format(value);
}

/** "1 channel", "12 channels". */
export function counted(count: number, one: string, many = `${one}s`): string {
	return `${number(count)} ${count === 1 ? one : many}`;
}

/** "Custom channels  3": a section's name with how many it holds, once it holds any. */
export function section(name: string, count: number): string {
	return count === 0 ? name : `${name}  ${count}`;
}

/** The kind of library Jellyfin calls it. */
export function libraryKind(kind: string): string {
	const kinds: Record<string, string> = {
		movies: 'Movies',
		tvshows: 'Series',
		musicvideos: 'Music videos',
		homevideos: 'Home videos',
		music: 'Music',
		books: 'Books',
		photos: 'Photos',
		playlists: 'Playlists',
		livetv: 'Live TV'
	};
	if (kind in kinds) return kinds[kind];
	if (['mixed', 'folders', 'unknown'].includes(kind)) return 'Mixed content';
	return kind.charAt(0).toUpperCase() + kind.slice(1);
}

/** What a library holds, by kind and count. */
export function itemKind(type: string, count: number): string {
	const one = count === 1;
	switch (type) {
		case 'SERIES':
			return 'series';
		case 'EPISODE':
			return one ? 'episode' : 'episodes';
		case 'MOVIE':
			return one ? 'movie' : 'movies';
		case 'MUSIC':
			return one ? 'song' : 'songs';
		case 'MUSIC_VIDEO':
			return one ? 'music video' : 'music videos';
		default:
			return type.toLowerCase();
	}
}
