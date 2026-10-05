<script lang="ts">
	let { size = 28 }: { size?: number } = $props();
</script>

<span class="brand">
	<img src="/favicon.svg" alt="" width={size} height={size} />
	<span class="word">Anstelle<span class="tv">TV</span></span>
</span>

<style>
	.brand {
		display: inline-flex;
		align-items: center;
		gap: 0.6rem;
	}

	.word {
		font-weight: 700;
		font-size: 1.1rem;
		letter-spacing: -0.01em;
		color: var(--text);
	}

	.tv {
		color: var(--primary);
	}
</style>
