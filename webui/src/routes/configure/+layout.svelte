<script lang="ts">
	import { page } from '$app/state';
	import { configurePages } from '#lib/nav.ts';

	let { children } = $props();
</script>

<div class="configure">
	<nav aria-label="Configure">
		{#each configurePages as item (item.slug)}
			{@const Icon = item.icon}
			<a
				href={`/configure/${item.slug}`}
				class:active={page.url.pathname === `/configure/${item.slug}`}
			>
				<Icon class="icon" />
				<span>{item.title}</span>
			</a>
		{/each}
	</nav>
	<section class="content">
		{@render children()}
	</section>
</div>

<style>
	.configure {
		display: grid;
		grid-template-columns: 14rem 1fr;
		gap: 1.5rem;
		align-items: start;
	}

	nav {
		position: sticky;
		top: 4.5rem;
		display: flex;
		flex-direction: column;
		gap: 0.15rem;
	}

	nav a {
		display: flex;
		align-items: center;
		gap: 0.65rem;
		padding: 0.5rem 0.75rem;
		border-radius: var(--radius-sm);
		color: var(--muted);
	}

	nav a:hover {
		color: var(--text);
		background: var(--surface);
	}

	nav a.active {
		color: var(--text);
		background: var(--surface-2);
	}

	.content {
		min-width: 0;
	}

	@media (max-width: 760px) {
		.configure {
			grid-template-columns: 1fr;
		}

		nav {
			position: static;
			flex-direction: row;
			overflow-x: auto;
			padding-bottom: 0.25rem;
		}

		nav a {
			white-space: nowrap;
		}
	}
</style>
