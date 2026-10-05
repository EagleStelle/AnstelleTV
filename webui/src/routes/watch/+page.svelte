<script lang="ts">
	import { api, type Channel } from '#lib/api.ts';
	import { live } from '#lib/live.svelte.ts';
	import Star from '~icons/ic/baseline-star';
	import { onMount } from 'svelte';

	let channels = $state<Channel[]>([]);
	let loaded = $state(false);

	async function load() {
		channels = await api.channels();
		loaded = true;
	}

	onMount(() => {
		void load();
		// Channel edits on the TV or in another browser show up here immediately.
		return live.subscribe(['channel'], () => void load());
	});
</script>

<div class="stack">
	<div class="heading">
		<h1>Watch</h1>
		<p class="muted">The live guide and in-browser playback arrive in development phase 10.</p>
	</div>

	{#if loaded && channels.length === 0}
		<section class="card">
			<h2>No channels yet</h2>
			<p class="muted">Connect a Jellyfin server on the TV and channels are created automatically.</p>
		</section>
	{:else}
		<ul class="channels">
			{#each channels as channel (channel.id)}
				<li class="card channel" class:hidden={channel.isHidden}>
					<span class="number">{channel.number}</span>
					<span class="name">{channel.name}</span>
					{#if channel.isFavorite}<Star class="icon fav" />{/if}
				</li>
			{/each}
		</ul>
	{/if}
</div>

<style>
	.heading {
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
	}

	.channels {
		list-style: none;
		margin: 0;
		padding: 0;
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr));
		gap: 0.75rem;
	}

	.channel {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.9rem 1rem;
	}

	.channel.hidden {
		opacity: 0.5;
	}

	.number {
		font-family: var(--mono);
		color: var(--primary);
		min-width: 2.5ch;
	}

	.name {
		flex: 1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.channel :global(.fav) {
		color: var(--accent);
	}
</style>
