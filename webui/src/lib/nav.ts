import type { Component } from 'svelte';
import Antenna from '~icons/ic/baseline-settings-input-antenna';
import Backup from '~icons/ic/baseline-backup';
import BarChart from '~icons/ic/baseline-bar-chart';
import Campaign from '~icons/ic/baseline-campaign';
import Category from '~icons/ic/baseline-category';
import Dns from '~icons/ic/baseline-dns';
import LiveTv from '~icons/ic/baseline-live-tv';
import Palette from '~icons/ic/baseline-palette';
import People from '~icons/ic/baseline-people';
import ReceiptLong from '~icons/ic/baseline-receipt-long';
import Schedule from '~icons/ic/baseline-schedule';

export interface ConfigurePage {
	slug: string;
	title: string;
	description: string;
	icon: Component;
	/** Development phase that delivers the page. */
	phase: number;
}

export const configurePages: ConfigurePage[] = [
	{ slug: 'channels', title: 'Channels', description: 'Build, number, hide and favorite channels.', icon: LiveTv, phase: 4 },
	{ slug: 'commercials', title: 'Commercials', description: 'Commercial, bumper and station ID sources.', icon: Campaign, phase: 4 },
	{ slug: 'groups', title: 'Groups', description: 'Organize the guide into categories.', icon: Category, phase: 4 },
	{ slug: 'schedules', title: 'Schedules', description: 'View and regenerate channel schedules.', icon: Schedule, phase: 3 },
	{ slug: 'themes', title: 'Themes', description: 'Pick a guide theme or design your own.', icon: Palette, phase: 8 },
	{ slug: 'profiles', title: 'Profiles', description: 'Household profiles, kids limits and PINs.', icon: People, phase: 8 },
	{ slug: 'servers', title: 'Media Servers', description: 'Jellyfin servers and libraries.', icon: Dns, phase: 2 },
	{ slug: 'livetv', title: 'Live TV', description: 'M3U, XMLTV, HDHomeRun and Jellyfin Live TV.', icon: Antenna, phase: 7 },
	{ slug: 'stats', title: 'Stats', description: 'What gets watched, by channel, show and profile.', icon: BarChart, phase: 9 },
	{ slug: 'logs', title: 'Logs', description: 'Recent events from the TV.', icon: ReceiptLong, phase: 9 },
	{ slug: 'backup', title: 'Backup', description: 'Export, import and copy from another TV.', icon: Backup, phase: 9 }
];
