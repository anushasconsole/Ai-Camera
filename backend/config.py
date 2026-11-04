"""
config.py – Application-wide settings loaded from environment / .env file.
All provider names and model IDs are configurable here so they can be
changed without touching any other source file.
"""

from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # ── Firebase ──────────────────────────────────────────────────────────
    # Local dev: set FIREBASE_SERVICE_ACCOUNT_PATH to the JSON file path.
    # Cloud deploy: set FIREBASE_SERVICE_ACCOUNT_JSON to the entire JSON string.
    firebase_service_account_path: str = Field(
        default="firebase-service-account.json",
        description="Path to Firebase Admin SDK JSON key (local dev).",
    )
    firebase_service_account_json: str = Field(
        default="",
        description="Full JSON content of service account key (cloud deploy).",
    )
    firebase_storage_bucket: str = Field(
        default="",
        description="Firebase Storage bucket name (e.g. my-project.appspot.com).",
    )

    # ── AI providers ─────────────────────────────────────────────────────
    gemini_api_key: str = Field(default="", description="Google Gemini API key.")
    groq_api_key: str = Field(default="", description="Groq API key.")

    # Model identifiers – change without modifying business logic
    gemini_model: str = Field(
        default="gemini-1.5-flash",
        description="Gemini model ID to use for vision analysis.",
    )
    groq_model: str = Field(
        default="meta-llama/llama-4-scout-17b-16e-instruct",
        description="Groq model ID to use for vision analysis.",
    )

    # Provider display names
    gemini_provider_name: str = "Google Gemini"
    groq_provider_name: str = "Groq Qwen Vision"

    # ── Server ────────────────────────────────────────────────────────────
    host: str = "0.0.0.0"
    port: int = 8000
    allowed_origins: str = "*"

    # ── Retry / resilience ────────────────────────────────────────────────
    ai_max_retries: int = 3
    ai_retry_wait_seconds: float = 2.0


settings = Settings()
