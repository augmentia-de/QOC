# Benchmark notes

This directory contains a small read-only Java project and prompts for comparing
the neutral built-in agent with the three specialized agents.

## Sample project
`java-sample/` is a minimal Maven project used as the review/research/refactor target.

## Tasks
See `tasks.txt`. All tasks are read-only and expect text output only.

## Running
1. Start QOC with `OPENAI_API_KEY` set.
2. Ensure the three agents are discovered from `agent/`.
3. Run your preferred client against each task for:
   - neutral built-in agent
   - reviewer
   - researcher
   - refactorer
4. Record latency, tokens, steps, and result quality in `results/`.

No files in `java-sample/` should be modified by the benchmark.
