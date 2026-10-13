<script lang="ts">
	import { api, problem, type Channel } from '#lib/api.ts';
	import { live } from '#lib/live.svelte.ts';
	import { onMount } from 'svelte';
	import LinkRow from './LinkRow.svelte';
	import Notice from './Notice.svelte';
	import Page from './Page.svelte';
	import Search from './Search.svelte';

	/**
	 * One kind of channel, by number: the ones someone built, or the ones made from the library,
	 * each opening its page, with a search over just these at the top. While it is typed, only what
	 * it finds is listed.
	 */
	let { library }: { library: boolean } = $props();

	let channels = $state<Channel[] | null>(null);
	let query = $state('');
	let error = $state<string | null>(null);

	async function load() {
		try {
			channels = (await api.channels()).channels;
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	onMount(() => {
		void load();
		return live.subscribe(['channel', 'channel_group', 'channel_season'], () => void load());
	});

	const needle = $derived(query.trim().toLowerCase());
	const kind = $derived((channels ?? []).filter((c) => c.fromLibrary === library));
	const shown = $derived(needle ? kind.filter((c) => c.name.toLowerCase().includes(needle) || String(c.number).startsWith(needle)) : kind);

	function summary(channel: Channel): string {
		return [
			channel.groupName,
			channel.isFavorite ? 'favorite' : null,
			channel.isHidden ? 'hidden' : null,
			channel.seasonLabel ? (channel.inSeason ? `on air ${channel.seasonLabel}` : `off season, on air ${channel.seasonLabel}`) : null
		]
			.filter(Boolean)
			.join(', ');
	}
</script>

<Page
	title={library ? 'Library' : 'Custom'}
	trail={[
		{ label: 'Home', href: '/' },
		{ label: 'Live TV', href: '/channels' }
	]}
	note={library ? 'Made from the library.' : 'Channels someone built.'}
>
	{#if error}<Notice text={error} error />{/if}
	<Search bind:value={query} label="Find a channel" />
	{#if channels && shown.length === 0}
		<Notice text={needle ? 'No channel has that name or number.' : library ? 'No library channels yet.' : 'No custom channels yet.'} />
	{/if}
	{#each shown as channel (channel.id)}
		<LinkRow title={channel.name} summary={summary(channel)} href="/channels/{channel.id}">
			{#snippet leading()}<span class="tabular min-w-10 text-title-m font-semibold text-text group-hover/row:text-on-accent group-focus-visible/row:text-on-accent">{channel.number}</span>{/snippet}
		</LinkRow>
	{/each}
</Page>
