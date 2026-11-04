
import argparse
import sqlite3

from inverted_index import search_term
from metadata import DB_PATH, create_metadata_db


def get_book_metadata(book_id):
    create_metadata_db()

    with sqlite3.connect(DB_PATH) as conn:
        result = conn.execute(
            """
            SELECT title, author, language
            FROM books
            WHERE book_id = ?
            """,
            (str(book_id),)
        ).fetchone()

    return result


def search(query):
    try:
        book_ids = search_term(query)
    except ValueError as error:
        print(f"Error: {error}")
        return

    if not book_ids:
        print(f"No se encontraron libros para '{query}'.")
        return

    print(f"\nResultados para '{query}': {len(book_ids)} libros\n")

    for book_id in book_ids:
        metadata = get_book_metadata(book_id)

        if metadata:
            title, author, language = metadata

            print(f"ID: {book_id}")
            print(f"Título: {title}")
            print(f"Autor: {author}")
            print(f"Idioma: {language}")
        else:
            print(f"ID: {book_id}")
            print("Metadatos no disponibles")

        print("-" * 50)


def main():
    parser = argparse.ArgumentParser(
        description="InverSeek - Search Engine"
    )

    parser.add_argument(
        "query",
        type=str,
        help="Término que se desea buscar"
    )

    args = parser.parse_args()
    search(args.query)


if __name__ == "__main__":
    main()
