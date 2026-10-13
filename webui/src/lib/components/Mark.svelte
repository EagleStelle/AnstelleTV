<script lang="ts">
	/** The channel bug: an "A" on a tile of the theme's accent, its crossbar running on past the right leg. */
	let { size = 32 }: { size?: number } = $props();
</script>

<svg width={size} height={size} viewBox="0 0 108 108" aria-hidden="true" class="shrink-0">
	<rect width="108" height="108" rx="6" class="fill-accent" />
	<path d="M45 28h10l18 52H63L50 41.5 37 80H27z" class="fill-on-accent" />
	<rect x="38" y="58" width="44" height="8" class="fill-on-accent" />
</svg>
