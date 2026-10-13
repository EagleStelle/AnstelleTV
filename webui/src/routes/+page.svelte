<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, problem, type Overview } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import Buttons from '#lib/components/Buttons.svelte';
	import LinkRow from '#lib/components/LinkRow.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Row from '#lib/components/Row.svelte';
	import Section from '#lib/components/Section.svelte';
	import { ago, counted } from '#lib/format.ts';
	import { live } from '#lib/live.svelte.ts';
	import { session } from '#lib/session.svelte.ts';
	import Backup from '~icons/material-symbols/settings-backup-restore';
	import Dns from '~icons/material-symbols/dns-outline';
	import LiveTv from '~icons/material-symbols/live-tv-outline';
	import Logout from '~icons/material-symbols/logout';
	import Palette from '~icons/material-symbols/palette-outline';
	import Person from '~icons/material-symbols/person-outline';
	import Wifi from '~icons/material-symbols/wifi';
	import { onMount } from 'svelte';

	let overview = $state<Overview | null>(null);
	let error = $state<string | null>(null);

	async function load() {
		try {
			overview = await api.overview();
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	onMount(() => {
		void load();
		return live.subscribe(['media_server', 'channel', 'theme', 'paired_client', 'setting', 'sync'], () => void load());
	});

	function serversLine(o: Overview): string {
		if (o.servers === 0) return 'None connected';
		if (o.serversNeedAttention) return 'Needs attention';
		return o.serverName ?? counted(o.servers, 'server');
	}

	function themesLine(o: Overview): string {
		return o.customThemes > 0 ? `${o.customThemes} custom, ${o.builtInThemes} built in` : `${o.builtInThemes} built in`;
	}

	async function unpair() {
		await session.logout();
		await goto('/pair');
	}
</script>

<Page
	title={session.session?.deviceName ?? 'AnstelleTV'}
	note="Manage this TV's servers, channels, themes and backups. Changes show on the TV at once, and changes made on the TV show here."
>
	{#if error}<Notice text={error} error />{/if}
	{#if overview}
		<LinkRow title="Servers" summary={serversLine(overview)} icon={Dns} href="/servers" />
		<LinkRow title="Channels" summary={overview.channels === 0 ? 'None yet' : counted(overview.channels, 'channel')} icon={LiveTv} href="/channels" />
		<LinkRow title="Themes" summary={themesLine(overview)} icon={Palette} href="/themes" />
		<LinkRow title="Web control" summary={counted(overview.pairedClients, 'paired browser')} icon={Wifi} href="/web" />
		<LinkRow
			title="Backup"
			summary={overview.backupSavedUtcMs ? `Saved ${ago(overview.backupSavedUtcMs)}` : 'Not saved yet'}
			icon={Backup}
			href="/backup"
		/>
	{/if}
	{#if session.session}
		<Section title="This browser" />
		<Row title={session.session.clientName} summary="Paired as {session.session.profile.name}, {session.session.profile.role}" icon={Person} />
		<Buttons><Button label="Unpair this browser" icon={Logout} onclick={unpair} /></Buttons>
	{/if}
</Page>
