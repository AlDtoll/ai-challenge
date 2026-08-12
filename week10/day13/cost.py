"""Cost calculator for LLM API usage."""
import os

PRICING = {
    "deepseek-chat": {
        "input_per_1m": float(os.getenv("DEEPSEEK_INPUT_PRICE", "0.14")),
        "output_per_1m": float(os.getenv("DEEPSEEK_OUTPUT_PRICE", "0.28")),
    },
}


def calculate(model: str, tokens_in: int, tokens_out: int) -> float:
    p = PRICING.get(model, PRICING["deepseek-chat"])
    return round(
        (tokens_in * p["input_per_1m"] + tokens_out * p["output_per_1m"]) / 1_000_000,
        6,
    )
