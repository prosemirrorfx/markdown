tags:: sample, notes

- ## Section heading inside an item
	- Child text
	  continuation under the child
	- >> A comment as a child block
		- >> ^^AI^^ (2026-01-15 09:30 UTC): Its answer, one level deeper.
		- >> ^^AI^^: And a second paragraph of it.
- #+BEGIN_QUERY
  {:title "Waiting"
   :query [:find (pull ?b [*]) :where [?b :block/content ?c]]}
  #+END_QUERY
- •  unicode bullet as text
- 
- last item, after an empty one
