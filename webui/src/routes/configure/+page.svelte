<script lang="ts">
	import { configurePages } from '#lib/nav.ts';
</script>

<div class="stack">
	<h1>Configure</h1>
	<div class="grid">
		{#each configurePages as item (item.slug)}
			{@const Icon = item.icon}
			<a class="card tile" href={`/configure/${item.slug}`}>
				<Icon class="icon tile-icon" />
				<h2>{item.title}</h2>
				<p class="muted">{item.description}</p>
			</a>
		{/each}
	</div>
</div>

<style>
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr));
		gap: 0.75rem;
	}

	.tile {
		display: flex;
		flex-direction: column;
		gap: 0.4rem;
		color: var(--text);
		transition: border-color 0.15s;
	}

	.tile:hover {
		border-color: var(--primary);
	}

	.tile :global(.tile-icon) {
		width: 1.75rem;
		height: 1.75rem;
		color: var(--primary);
	}
</style>
