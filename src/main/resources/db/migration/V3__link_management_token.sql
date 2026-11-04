-- Existing links remain public redirects, but cannot be managed without an issued token.
ALTER TABLE link ADD COLUMN management_token_hash varchar(64);
