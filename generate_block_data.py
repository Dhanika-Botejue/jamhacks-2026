#!/usr/bin/env python3
"""Generate step-by-step block data for blueprint-only circuit JSON files."""

import argparse
import json
import os
import re
import time
from pathlib import Path

import anthropic

CIRCUITS_DIR = Path(__file__).parent / "src/main/resources/rouge/circuits"

FEW_SHOT_IDS = ["not-gate", "rs-latch", "xor-gate", "piston-push"]

SYSTEM_PROMPT = """You are a Minecraft redstone expert generating step-by-step block data for the Rouge mod's circuit library.

Each circuit JSON has a "steps" array. Each step is cumulative — it contains ALL blocks placed so far (not just the new ones). Steps teach the player how to build the circuit incrementally.

Rules:
- Coordinates start at (0,0,0). Keep all blocks within the circuit's stated footprint (WxHxD = x × y × z).
- Use real Minecraft 1.21.x block IDs with correct blockstate properties (e.g. minecraft:sticky_piston[facing=north,extended=false]).
- Every block string must start with "minecraft:".
- 3 to 7 steps total. Each step adds meaningful new blocks.
- Each step has: "title" (short name), "explanation" (1-2 sentences teaching WHY, not just what), "blocks" (full cumulative list).
- Use stone (minecraft:stone) for structural/support blocks.
- Common blocks: minecraft:redstone_wire, minecraft:redstone_wall_torch[facing=east,lit=true], minecraft:redstone_torch[lit=true], minecraft:sticky_piston[facing=up,extended=false], minecraft:piston[facing=up,extended=false], minecraft:piston_head[facing=up,type=normal], minecraft:observer[facing=up,powered=false], minecraft:comparator[facing=south,mode=compare,powered=false], minecraft:repeater[delay=1,facing=south,locked=false,powered=false], minecraft:hopper[facing=down], minecraft:dropper[facing=up,triggered=false], minecraft:chest[facing=south,type=single], minecraft:furnace[facing=south,lit=false], minecraft:lever[face=floor,facing=south,powered=false], minecraft:stone_button[face=floor,facing=south,powered=false], minecraft:redstone_lamp, minecraft:slime_block, minecraft:honey_block, minecraft:note_block, minecraft:dispenser[facing=up,triggered=false].

Return ONLY a valid JSON array of steps. No markdown fences, no explanation outside the JSON.
"""

def load_few_shot_examples() -> str:
    parts = []
    for fid in FEW_SHOT_IDS:
        path = CIRCUITS_DIR / f"{fid}.json"
        if path.exists():
            data = json.loads(path.read_text())
            if data.get("steps"):
                parts.append(f"### Example: {data['title']}\n```json\n{json.dumps({'id': data['id'], 'footprint': data['footprint'], 'steps': data['steps']}, indent=2)}\n```")
    return "\n\n".join(parts)

def find_blueprints() -> list[Path]:
    blueprints = []
    for p in sorted(CIRCUITS_DIR.glob("*.json")):
        data = json.loads(p.read_text())
        if not data.get("steps"):
            blueprints.append(p)
    return blueprints

def parse_steps(text: str) -> list[dict]:
    text = text.strip()
    text = re.sub(r"^```(?:json)?\s*", "", text)
    text = re.sub(r"\s*```$", "", text)
    text = text.strip()
    return json.loads(text)

def validate_steps(steps: list[dict]) -> str | None:
    if not steps:
        return "empty steps"
    for i, step in enumerate(steps):
        for field in ("title", "explanation", "blocks"):
            if field not in step:
                return f"step {i} missing '{field}'"
        for j, b in enumerate(step["blocks"]):
            for coord in ("x", "y", "z"):
                if coord not in b:
                    return f"step {i} block {j} missing '{coord}'"
            if "block" not in b:
                return f"step {i} block {j} missing 'block'"
            if not b["block"].startswith("minecraft:"):
                return f"step {i} block {j} invalid id '{b['block']}'"
    return None

def generate_steps(client: anthropic.Anthropic, blueprint: dict, examples: str) -> list[dict]:
    user_msg = f"""Generate block data steps for this circuit:

{json.dumps({k: blueprint[k] for k in blueprint if k != 'steps'}, indent=2)}

{examples}

Return ONLY the JSON array of steps. The blocks must fit within the footprint {blueprint.get('footprint', 'unknown')} (width x height x depth = x × y × z range)."""

    response = client.messages.create(
        model="claude-sonnet-4-6",
        max_tokens=8192,
        system=SYSTEM_PROMPT,
        messages=[{"role": "user", "content": user_msg}],
    )
    return parse_steps(response.content[0].text)

def main():
    parser = argparse.ArgumentParser(description="Generate block data for blueprint circuits")
    parser.add_argument("--dry-run", action="store_true", help="Print output without writing files")
    parser.add_argument("--only", help="Comma-separated list of circuit IDs to process")
    parser.add_argument("--delay", type=float, default=1.0, help="Seconds between API calls")
    args = parser.parse_args()

    api_key = os.environ.get("ANTHROPIC_API_KEY")
    if not api_key:
        raise SystemExit("ANTHROPIC_API_KEY environment variable not set")

    client = anthropic.Anthropic(api_key=api_key)
    examples = load_few_shot_examples()
    blueprints = find_blueprints()

    if args.only:
        only_ids = set(args.only.split(","))
        blueprints = [p for p in blueprints if p.stem in only_ids]

    print(f"Processing {len(blueprints)} blueprints...")

    ok, failed = 0, []
    for path in blueprints:
        data = json.loads(path.read_text())
        cid = data["id"]
        print(f"  [{cid}] generating...", end=" ", flush=True)
        try:
            steps = generate_steps(client, data, examples)
            err = validate_steps(steps)
            if err:
                print(f"INVALID ({err})")
                failed.append((cid, err))
                continue
            data["steps"] = steps
            if args.dry_run:
                print(f"OK ({len(steps)} steps) [dry-run]")
                print(json.dumps(steps, indent=2)[:500] + "...")
            else:
                path.write_text(json.dumps(data, indent=2) + "\n")
                print(f"OK ({len(steps)} steps)")
            ok += 1
        except Exception as e:
            print(f"ERROR: {e}")
            failed.append((cid, str(e)))
        time.sleep(args.delay)

    print(f"\nDone: {ok} succeeded, {len(failed)} failed")
    if failed:
        print("Failed:")
        for cid, reason in failed:
            print(f"  {cid}: {reason}")

if __name__ == "__main__":
    main()
