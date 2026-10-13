// OKLCH, as the theme designer edits colors: the same math as core/model's Oklch.kt, so a ladder
// moves the swatch at once while the TV saves and checks the theme.

export interface Oklch {
	/** 0 to 1. */
	lightness: number;
	/** 0 to about 0.37. */
	chroma: number;
	/** Degrees. */
	hue: number;
}

export const MAX_CHROMA = 0.37;
const EPSILON = 1e-6;

function decode(channel: number): number {
	const c = channel / 255;
	return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function encode(linear: number): number {
	const x = Math.min(Math.max(linear, 0), 1);
	const v = x <= 0.0031308 ? 12.92 * x : 1.055 * x ** (1 / 2.4) - 0.055;
	return Math.round(v * 255);
}

function linear(l: number, c: number, h: number): [number, number, number] {
	const a = c * Math.cos((h * Math.PI) / 180);
	const b = c * Math.sin((h * Math.PI) / 180);
	const l3 = (l + 0.3963377774 * a + 0.2158037573 * b) ** 3;
	const m3 = (l - 0.1055613458 * a - 0.0638541728 * b) ** 3;
	const s3 = (l - 0.0894841775 * a - 1.291485548 * b) ** 3;
	return [
		4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
		-1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
		-0.0041960863 * l3 - 0.7034186147 * m3 + 1.707614701 * s3
	];
}

/** `#RRGGBB` or `#AARRGGBB` (alpha ignored) as OKLCH. */
export function fromHex(hex: string): Oklch {
	const rgb = parseInt(hex.replace('#', '').slice(-6), 16);
	const r = decode((rgb >> 16) & 0xff);
	const g = decode((rgb >> 8) & 0xff);
	const b = decode(rgb & 0xff);
	const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
	const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
	const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
	const lightness = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s;
	const a = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s;
	const bb = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s;
	const chroma = Math.hypot(a, bb);
	const hue = chroma < 1e-4 ? 0 : ((Math.atan2(bb, a) * 180) / Math.PI + 360) % 360;
	return { lightness, chroma, hue };
}

/** As `#RRGGBB`, chroma reduced until the color fits sRGB. */
export function toHex({ lightness, chroma, hue }: Oklch): string {
	let c = Math.max(chroma, 0);
	let rgb = linear(lightness, c, hue);
	while (c > 0 && rgb.some((v) => v < -EPSILON || v > 1 + EPSILON)) {
		c = Math.max(c - 0.002, 0);
		rgb = linear(lightness, c, hue);
	}
	return '#' + rgb.map((v) => encode(v).toString(16).padStart(2, '0').toUpperCase()).join('');
}

/** True for `#RRGGBB` or `#AARRGGBB`. */
export function isColor(hex: string): boolean {
	return /^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/.test(hex);
}
