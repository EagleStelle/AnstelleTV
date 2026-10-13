<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, ApiError, problem, type ServerDetail } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import Buttons from '#lib/components/Buttons.svelte';
	import DangerButton from '#lib/components/DangerButton.svelte';
	import Facts from '#lib/components/Facts.svelte';
	import Figure from '#lib/components/Figure.svelte';
	import Figures from '#lib/components/Figures.svelte';
	import LinkRow from '#lib/components/LinkRow.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Readout from '#lib/components/Readout.svelte';
	import Row from '#lib/components/Row.svelte';
	import Rule from '#lib/components/Rule.svelte';
	import Section from '#lib/components/Section.svelte';
	import StatusLine from '#lib/components/StatusLine.svelte';
	import SwitchRow from '#lib/components/SwitchRow.svelte';
	import { ago, itemKind, libraryKind } from '#lib/format.ts';
	import { live } from '#lib/live.svelte.ts';
	import Login from '~icons/material-symbols/login';
	import Person from '~icons/material-symbols/person-outline';
	import Refresh from '~icons/material-symbols/refresh';
	import Sync from '~icons/material-symbols/sync';
	import VideoLibrary from '~icons/material-symbols/video-library-outline';
	import VideocamOff from '~icons/material-symbols/videocam-off-outline';
	import { onMount } from 'svelte';

	const id = $derived(Number(page.params.id));
	let detail = $state<ServerDetail | null>(null);
	let error = $state<string | null>(null);
	let gone = $state(false);

	async function load() {
		try {
			detail = await api.server(id);
			error = null;
		} catch (e) {
			if (e instanceof ApiError && e.status === 404) gone = true;
			else error = problem(e);
		}
	}

	$effect(() => {
		if (id) void load();
	});

	onMount(() => live.subscribe(['media_server', 'library', 'media_item', 'sync'], () => void load()));

	async function act(action: () => Promise<unknown>) {
		try {
			await action();
			error = null;
			await load();
		} catch (e) {
			error = problem(e);
		}
	}

	/** What the library holds: movies, then series joined to their episodes, then anything else. */
	const counts = $derived.by(() => {
		const held = new Map((detail?.counts ?? []).map((c) => [c.type, c.count]));
		const top = ['MOVIE', 'SERIES', 'EPISODE'];
		const rest = [...held.entries()].filter(([type]) => !top.includes(type));
		return { movies: held.get('MOVIE'), series: held.get('SERIES'), episodes: held.get('EPISODE'), rest };
	});

	const signInHref = $derived(detail ? `/servers/add?address=${encodeURIComponent(detail.server.address)}` : '/servers/add');
</script>

{#if gone}
	<Page title="Server" trail={[{ label: 'Home', href: '/' }, { label: 'Servers', href: '/servers' }]}>
		<Notice text="This server is no longer connected." />
	</Page>
{:else if detail}
	{@const server = detail.server}
	<Page
		title={server.name}
		trail={[{ label: 'Home', href: '/' }, { label: 'Servers', href: '/servers' }]}
		note="Sync now fetches what changed; Full sync reads every item again. A library turned off is dropped at the next sync."
	>
		{#snippet actions()}
			{#if server.isSignedIn}
				<Button label="Sync now" icon={Sync} primary onclick={() => act(() => api.sync(id, false))} />
				<Button label="Full sync" icon={Refresh} onclick={() => act(() => api.sync(id, true))} />
			{:else}
				<Button label="Sign in" icon={Login} primary href={signInHref} />
			{/if}
		{/snippet}
		{#snippet overview()}
			<Readout>
				{#if server.progress}
					<StatusLine label="Syncing" text={server.progress} state="off" />
				{:else if !server.isSignedIn}
					<StatusLine label="Signed out" state="problem" />
				{:else if server.lastSyncError}
					<StatusLine label="Sync failed" text={server.lastSyncError} state="problem" />
				{:else if server.lastSyncUtcMs}
					<StatusLine label="Synced" text={ago(server.lastSyncUtcMs)} />
				{:else}
					<StatusLine label="Not synced yet" state="off" />
				{/if}
				{#if detail!.counts.length === 0}
					<p class="text-text-muted">No items yet</p>
				{:else}
					<Figures>
						{#if counts.movies}<Figure value={counts.movies} name={itemKind('MOVIE', counts.movies)} lead />{/if}
						{#if counts.series}<Figure value={counts.series} name="series" lead={!counts.movies} />{/if}
						{#if counts.episodes}<Figure value={counts.episodes} name={itemKind('EPISODE', counts.episodes)} />{/if}
						{#each counts.rest as [type, count] (type)}<Figure value={count} name={itemKind(type, count)} />{/each}
					</Figures>
				{/if}
				<Rule />
				<Facts
					items={[
						...(server.version ? [['Server version', `Jellyfin ${server.version}`] as [string, string]] : []),
						...(server.userName ? [['Signed in as', server.userName] as [string, string]] : []),
						['Address', server.address]
					]}
				/>
			</Readout>
		{/snippet}

		{#if error}<Notice text={error} error />{/if}
		<Section title="Libraries" />
		{#each detail.libraries as library (library.id)}
			{#if library.canAir}
				<SwitchRow
					title={library.name}
					summary={libraryKind(library.kind)}
					icon={VideoLibrary}
					checked={library.enabled}
					onchange={(enabled) => act(() => api.setLibrary(id, library.id, enabled))}
				/>
			{:else}
				<Row title={library.name} summary="{libraryKind(library.kind)}, not supported yet" icon={VideocamOff} />
			{/if}
		{/each}
		{#if detail.libraries.length === 0}<Notice text="No libraries yet." />{/if}
		<Section title="Account" />
		<LinkRow title="Jellyfin user" summary={server.userName ?? 'Not signed in'} icon={Person} href={signInHref} />
		<Buttons foot>
			<DangerButton
				label="Remove server"
				question="Remove {server.name}?"
				explanation="Signs out and deletes everything synced from it."
				confirm="Remove"
				onconfirm={() => act(async () => {
					await api.removeServer(id);
					await goto('/servers');
				})}
			/>
		</Buttons>
	</Page>
{:else if error}
	<Page title="Server" trail={[{ label: 'Home', href: '/' }, { label: 'Servers', href: '/servers' }]}>
		<Notice text={error} error />
	</Page>
{/if}
