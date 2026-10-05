<script lang="ts">
	import '../app.css';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import Brand from '#lib/components/Brand.svelte';
	import { live } from '#lib/live.svelte.ts';
	import { session } from '#lib/session.svelte.ts';
	import Logout from '~icons/ic/baseline-logout';
	import Tune from '~icons/ic/baseline-tune';
	import LiveTv from '~icons/material-symbols/live-tv-outline';
	import { onMount } from 'svelte';

	let { children } = $props();

	const onPairPage = $derived(page.url.pathname.startsWith('/pair'));

	onMount(() => {
		void session.refresh();
		return () => live.stop();
	});

	// Unpaired browsers go to the pairing page; paired ones get live updates.
	$effect(() => {
		if (session.status === 'unpaired' && !onPairPage) void goto('/pair');
		if (session.status === 'paired') live.start();
	});

	async function logout() {
		live.stop();
		await session.logout();
		await goto('/pair');
	}
</script>

{#if onPairPage}
	{@render children()}
{:else}
	<header class="top">
		<a href="/watch" class="home"><Brand /></a>
		<nav aria-label="Main">
			<a href="/watch" class:active={page.url.pathname.startsWith('/watch')}>
				<LiveTv class="icon" /> Watch
			</a>
			<a href="/configure" class:active={page.url.pathname.startsWith('/configure')}>
				<Tune class="icon" /> Configure
			</a>
		</nav>
		<div class="status">
			{#if session.status === 'paired'}
				<span class="dot" class:on={live.connected} title={live.connected ? 'Live' : 'Reconnecting'}></span>
				<span class="muted device">{session.session?.deviceName}</span>
				<button class="btn icon-btn" onclick={logout} title="Unpair this browser">
					<Logout class="icon" />
				</button>
			{:else if session.status === 'offline'}
				<span class="muted">TV not reachable</span>
			{/if}
		</div>
	</header>
	<main>
		{#if session.status === 'paired'}
			{@render children()}
		{:else if session.status === 'offline'}
			<section class="card">
				<h2>Can't reach the TV</h2>
				<p class="muted">Make sure AnstelleTV is open on the TV and web control is turned on.</p>
				<button class="btn" onclick={() => session.refresh()}>Try again</button>
			</section>
		{/if}
	</main>
{/if}

<style>
	.top {
		position: sticky;
		top: 0;
		z-index: 10;
		display: flex;
		align-items: center;
		gap: 1.5rem;
		padding: 0.75rem 1.5rem;
		background: color-mix(in srgb, var(--bg) 88%, transparent);
		backdrop-filter: blur(8px);
		border-bottom: 1px solid var(--line);
	}

	nav {
		display: flex;
		gap: 0.25rem;
	}

	nav a {
		display: inline-flex;
		align-items: center;
		gap: 0.4rem;
		padding: 0.45rem 0.8rem;
		border-radius: var(--radius-sm);
		color: var(--muted);
	}

	nav a:hover {
		color: var(--text);
	}

	nav a.active {
		color: var(--text);
		background: var(--surface-2);
	}

	.status {
		margin-left: auto;
		display: flex;
		align-items: center;
		gap: 0.6rem;
	}

	.dot {
		width: 0.55rem;
		height: 0.55rem;
		border-radius: 50%;
		background: var(--muted);
	}

	.dot.on {
		background: var(--ok);
	}

	.icon-btn {
		padding: 0;
		width: 2.5rem;
	}

	main {
		max-width: 1200px;
		margin: 0 auto;
		padding: 1.5rem;
	}

	@media (max-width: 640px) {
		.top {
			flex-wrap: wrap;
			gap: 0.75rem;
			padding: 0.75rem 1rem;
		}

		.device {
			display: none;
		}

		main {
			padding: 1rem;
		}
	}
</style>
