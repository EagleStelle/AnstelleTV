<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { api, ApiError, problem, type ServerDetail } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import Buttons from '#lib/components/Buttons.svelte';
	import ChoiceRow from '#lib/components/ChoiceRow.svelte';
	import { session } from '#lib/session.svelte.ts';
	import DangerButton from '#lib/components/DangerButton.svelte';
	import Fact from '#lib/components/Fact.svelte';
	import Facts from '#lib/components/Facts.svelte';
	import Figure from '#lib/components/Figure.svelte';
	import Figures from '#lib/components/Figures.svelte';
	import LinkRow from '#lib/components/LinkRow.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Readout from '#lib/components/Readout.svelte';
	import Row from '#lib/components/Row.svelte';
	import Rule from '#lib/components/Rule.svelte';
	import ScanLadder from '#lib/components/ScanLadder.svelte';
	import Section from '#lib/components/Section.svelte';
	import SeriesFigure from '#lib/components/SeriesFigure.svelte';
	import StatusLine from '#lib/components/StatusLine.svelte';
	import SwitchRow from '#lib/components/SwitchRow.svelte';
	import { ago, itemKind, libraryKind } from '#lib/format.ts';
	import { live } from '#lib/live.svelte.ts';
	import Dns from '~icons/material-symbols/dns';
	import Login from '~icons/material-symbols/login';
	import Person from '~icons/material-symbols/person';
	import Public from '~icons/material-symbols/public';
	import Refresh from '~icons/material-symbols/refresh';
	import Sync from '~icons/material-symbols/sync';
	import VideoLibrary from '~icons/material-symbols/video-library';
	import VideocamOff from '~icons/material-symbols/videocam-off';
	import { onMount } from 'svelte';

	const id = $derived(Number(page.params.id));
	let detail = $state<ServerDetail | null>(null);
	let error = $state<string | null>(null);
	let gone = $state(false);
	let selectedAdmin = $state<number | null>(null);
	let removing = $state(false);
	const removal = $derived(detail?.removal);
	const admin = $derived(removal?.admins.find((admin) => admin.id === selectedAdmin));

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

	onMount(() => live.subscribe(['media_server', 'library', 'media_item', 'profile', 'sync'], () => void load()));

	async function remove() {
		if (removing) return;
		removing = true;
		try {
			await api.removeServer(id, removal?.requiresTransfer ? admin?.id : undefined);
			await session.refresh();
			await goto('/servers');
		} catch (e) {
			await load();
			error = problem(e);
		} finally {
			removing = false;
		}
	}

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
		note="Full sync reads every item again."
	>
		<!--
			What the server is and how its sync stands, read like a set's system-information panel: the
			sync state set like the banner's NOW line, what the library holds in tabular figures, then the
			connection's facts, each behind the icon that names it.
		-->
		{#snippet actions()}
			{#if server.isSignedIn}
				<Button label="Sync now" icon={Sync} primary onclick={() => act(() => api.sync(id, false))} />
				<Button label="Full sync" icon={Refresh} onclick={() => act(() => api.sync(id, true))} />
			{:else}
				<Button label="Sign in" icon={Login} primary href={signInHref} />
			{/if}
		{/snippet}
		{#snippet overview()}
			<Readout roomy>
				{#if server.progress}
					<div class="flex flex-col gap-2.5">
						<StatusLine label="Syncing" text={server.progress} state="off" />
						<ScanLadder />
					</div>
				{:else if !server.isSignedIn || server.lastSyncError}
					<div class="flex flex-col gap-1.5">
						<StatusLine label={server.isSignedIn ? 'Sync failed' : 'Signed out'} state="problem" />
						{#if server.isSignedIn && server.lastSyncError}<p class="text-body-m">{server.lastSyncError}</p>{/if}
					</div>
				{:else if server.lastSyncUtcMs}
					<StatusLine label="Synced" text={ago(server.lastSyncUtcMs)} />
				{:else}
					<StatusLine label="Not synced yet" state="off" />
				{/if}
				{#if detail!.counts.length === 0}
					<p class="text-body-m text-text-muted">No items yet</p>
				{:else}
					<!-- Movies, then series joined to their episodes, then anything else. -->
					<Figures wide>
						{#if counts.movies}<Figure value={counts.movies} name={itemKind('MOVIE', counts.movies)} />{/if}
						{#if counts.series && counts.episodes}
							<SeriesFigure series={counts.series} episodes={counts.episodes} />
						{:else if counts.series}
							<Figure value={counts.series} name="series" />
						{:else if counts.episodes}
							<Figure value={counts.episodes} name={itemKind('EPISODE', counts.episodes)} />
						{/if}
						{#each counts.rest as [type, count] (type)}<Figure value={count} name={itemKind(type, count)} />{/each}
					</Figures>
				{/if}
				<Rule />
				<Facts wide>
					{#if server.version}<Fact icon={Dns} text="Jellyfin {server.version}" label="Server version" />{/if}
					{#if server.userName}<Fact icon={Person} text={server.userName} label="Signed in as" />{/if}
					<Fact icon={Public} text={server.address} label="Address" />
				</Facts>
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
		{#if removal?.requiresTransfer}
			<Section title="Transfer ownership" />
			{#if !session.isOwner}
				<Notice text="The owner must transfer ownership before this server can be removed." />
			{:else if removal.admins.length === 0}
				<Notice text="Sign in an admin on another server before removing this server. The new owner must remain signed in." />
			{:else}
				<ChoiceRow
					title="New owner"
					value={selectedAdmin}
					choices={removal.admins.map((admin) => admin.id)}
					label={(id) => removal?.admins.find((admin) => admin.id === id)?.name ?? 'Choose an admin'}
					onselect={(id) => selectedAdmin = id}
					about="Transfer ownership to this admin when the server is removed."
				/>
			{/if}
		{/if}
		{#if removal?.resetsApp && !session.isOwner}
			<Notice text="Only the owner can remove the last server and reset the app." />
		{/if}
		{#if removal && !removing && (!removal.requiresTransfer || (session.isOwner && admin)) && (!removal.resetsApp || session.isOwner)}
		<Buttons foot>
			<DangerButton
				label="Remove server"
				question={removal.resetsApp ? `Remove ${server.name} and start over?` : `Remove ${server.name}?`}
				explanation={removal.resetsApp
					? 'Deletes all servers, profiles, history, channels and settings, including signed-out entries. Returns to first-time setup.'
					: removal.requiresTransfer
						? `Makes ${admin?.name} the owner, signs out and deletes this server's accounts and profiles without another valid account.`
						: 'Signs out and deletes everything synced from it, its accounts and profiles without another valid account.'}
				confirm="Remove"
				onconfirm={remove}
			/>
		</Buttons>
		{/if}
	</Page>
{:else if error}
	<Page title="Server" trail={[{ label: 'Home', href: '/' }, { label: 'Servers', href: '/servers' }]}>
		<Notice text={error} error />
	</Page>
{/if}
