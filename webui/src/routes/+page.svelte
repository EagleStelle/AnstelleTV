<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, problem, type Backup, type Overview, type Web } from '#lib/api.ts';
	import BackupReadout from '#lib/components/BackupReadout.svelte';
	import Button from '#lib/components/Button.svelte';
	import Buttons from '#lib/components/Buttons.svelte';
	import ChannelsReadout from '#lib/components/ChannelsReadout.svelte';
	import LinkRow from '#lib/components/LinkRow.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import OnDemandReadout from '#lib/components/OnDemandReadout.svelte';
	import Page from '#lib/components/Page.svelte';
	import Row from '#lib/components/Row.svelte';
	import Section from '#lib/components/Section.svelte';
	import ServersReadout from '#lib/components/ServersReadout.svelte';
	import ThemePreview from '#lib/components/ThemePreview.svelte';
	import WebReadout from '#lib/components/WebReadout.svelte';
	import { ago, counted } from '#lib/format.ts';
	import { live } from '#lib/live.svelte.ts';
	import { session } from '#lib/session.svelte.ts';
	import { theme } from '#lib/theme.svelte.ts';
	import Dns from '~icons/material-symbols/dns';
	import LiveTv from '~icons/material-symbols/live-tv';
	import Logout from '~icons/material-symbols/logout';
	import Movie from '~icons/material-symbols/movie';
	import Palette from '~icons/material-symbols/palette';
	import Person from '~icons/material-symbols/person';
	import Restore from '~icons/material-symbols/settings-backup-restore';
	import Wifi from '~icons/material-symbols/wifi';
	import { onMount } from 'svelte';

	let overview = $state<Overview | null>(null);
	let web = $state<Web | null>(null);
	let backup = $state<Backup | null>(null);
	let error = $state<string | null>(null);

	async function load() {
		try {
			[overview, web, backup] = await Promise.all([api.overview(), api.web(), api.backup()]);
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	onMount(() => {
		void load();
		return live.subscribe(
			['media_server', 'media_item', 'channel', 'channel_group', 'theme', 'profile', 'paired_client', 'setting', 'watch_event', 'sync'],
			() => void load()
		);
	});

	function serversLine(servers: Overview['servers']): string {
		if (servers.count === 0) return 'None connected';
		if (servers.needAttention > 0) return 'Needs attention';
		return servers.name ?? counted(servers.count, 'server');
	}

	async function unpair() {
		await session.logout();
		await goto('/pair');
	}
</script>

<!--
	The whole TV at a glance: each area's row (what it opens, its state under it) over its readout,
	two areas abreast from 840 px. A browser has room to show every readout at once, where the TV
	shows the one focused; phones keep the rows alone, as the app's Settings home does.
-->
<Page
	title={session.session?.deviceName ?? 'AnstelleTV'}
	note="Manage this TV's servers, channels, On Demand, themes and backups. Changes show on the TV at once, and changes made on the TV show here."
	wide
>
	{#if error}<Notice text={error} error />{/if}
	{#if overview && web && backup}
		{@const areas = [
			{ title: 'Servers', summary: serversLine(overview.servers), icon: Dns, href: '/servers' },
			{ title: 'Live TV', summary: overview.channels.total === 0 ? 'None yet' : counted(overview.channels.total, 'channel'), icon: LiveTv, href: '/channels' },
			{ title: 'On Demand', summary: overview.onDemand.summary, icon: Movie, href: '/ondemand' },
			{ title: 'Themes', summary: theme.spec?.name, icon: Palette, href: '/themes' },
			{ title: 'Web control', summary: web.urls[0] ? `On at ${web.urls[0]}` : 'On', icon: Wifi, href: '/web' },
			{ title: 'Backup', summary: backup.savedUtcMs ? `Saved ${ago(backup.savedUtcMs)}` : 'Not saved yet', icon: Restore, href: '/backup' }
		]}
		<div class="grid items-start gap-y-0.5 min-[840px]:grid-cols-2 min-[840px]:gap-x-8 min-[840px]:gap-y-10 min-[840px]:pt-4">
			{#each areas as area (area.href)}
				<div class="flex min-w-0 flex-col gap-2">
					<LinkRow title={area.title} summary={area.summary} icon={area.icon} href={area.href} />
					<div class="mx-4 hidden min-[840px]:block">
						{#if area.href === '/servers'}
							<ServersReadout servers={overview.servers} />
						{:else if area.href === '/channels'}
							<ChannelsReadout stats={overview.channels} />
						{:else if area.href === '/ondemand'}
							<OnDemandReadout onDemand={overview.onDemand} />
						{:else if area.href === '/themes'}
							{#if theme.spec}<ThemePreview spec={theme.spec} />{/if}
						{:else if area.href === '/web'}
							<WebReadout {web} />
						{:else}
							<BackupReadout {backup} />
						{/if}
					</div>
				</div>
			{/each}
		</div>
	{/if}
	{#if session.session}
		<div class="min-[840px]:max-w-1/2">
			<Section title="This browser" />
			<Row title={session.session.clientName} summary="Paired as {session.session.profile.name}, {session.session.profile.role}" icon={Person} />
			<Buttons><Button label="Unpair this browser" icon={Logout} onclick={unpair} /></Buttons>
		</div>
	{/if}
</Page>
