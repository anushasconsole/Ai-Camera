"""
groq_client.py
Streams a response from the Groq vision API for a list of images + prompt.
Groq's vision endpoint accepts images as base64 data URIs.
When the provider requires batching (max 3 images per call), multiple
requests are made sequentially and their outputs are combined.
"""

from __future__ import annotations

import asyncio
import base64
import logging
from typing import AsyncGenerator

from groq import AsyncGroq, RateLimitError, APIStatusError

from config import settings

logger = logging.getLogger(__name__)

_client = AsyncGroq(api_key=settings.groq_api_key)

# Groq vision models accept up to 3 images per request.
_MAX_IMAGES_PER_REQUEST = 3


def _encode_image(image_bytes: bytes) -> str:
    """Return a base-64 data URI for an image/jpeg blob."""
    b64 = base64.b64encode(image_bytes).decode("utf-8")
    return f"data:image/jpeg;base64,{b64}"


def _build_messages(image_bytes_list: list[bytes], prompt: str) -> list[dict]:
    """Build the OpenAI-compatible messages array with inline images."""
    content: list[dict] = []

    for img_bytes in image_bytes_list:
        content.append(
            {
                "type": "image_url",
                "image_url": {"url": _encode_image(img_bytes)},
            }
        )

    content.append({"type": "text", "text": prompt})

    return [{"role": "user", "content": content}]


async def _stream_single_batch(
    image_bytes_list: list[bytes],
    prompt: str,
    attempt: int = 1,
) -> AsyncGenerator[str, None]:
    """Stream one request (≤ _MAX_IMAGES_PER_REQUEST images)."""
    messages = _build_messages(image_bytes_list, prompt)
    max_attempts = settings.ai_max_retries

    while attempt <= max_attempts:
        try:
            stream = await _client.chat.completions.create(
                model=settings.groq_model,
                messages=messages,
                stream=True,
                max_tokens=4096,
                temperature=0.4,
            )
            async for chunk in stream:
                delta = chunk.choices[0].delta
                if delta and delta.content:
                    yield delta.content
            return  # success

        except RateLimitError as exc:
            logger.warning("Groq rate-limited (attempt %d/%d): %s", attempt, max_attempts, exc)
            if attempt >= max_attempts:
                raise RuntimeError(
                    f"Groq quota exhausted after {max_attempts} attempts: {exc}"
                ) from exc
            await asyncio.sleep(settings.ai_retry_wait_seconds * attempt)
            attempt += 1

        except APIStatusError as exc:
            logger.error("Groq API error (status %s): %s", exc.status_code, exc)
            raise RuntimeError(f"Groq API error {exc.status_code}: {exc.message}") from exc

        except Exception as exc:
            logger.error("Groq unexpected error: %s", exc)
            raise RuntimeError(f"Groq error: {exc}") from exc


async def stream_groq(
    image_bytes_list: list[bytes],
    prompt: str,
) -> AsyncGenerator[str, None]:
    """
    Yield text chunks from Groq as they are produced.
    Automatically splits large image lists into batches of _MAX_IMAGES_PER_REQUEST.
    """
    # Split into chunks of at most _MAX_IMAGES_PER_REQUEST
    batches = [
        image_bytes_list[i : i + _MAX_IMAGES_PER_REQUEST]
        for i in range(0, len(image_bytes_list), _MAX_IMAGES_PER_REQUEST)
    ]

    for batch_index, batch in enumerate(batches):
        if batch_index > 0:
            # Separator between batches so the model knows it's a continuation
            yield f"\n\n[Continuing analysis for images {batch_index * _MAX_IMAGES_PER_REQUEST + 1}–"
            yield f"{min((batch_index + 1) * _MAX_IMAGES_PER_REQUEST, len(image_bytes_list))}]\n\n"

        async for chunk in _stream_single_batch(batch, prompt):
            yield chunk
