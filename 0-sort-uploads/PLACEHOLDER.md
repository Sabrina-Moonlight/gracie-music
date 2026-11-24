# 0-sort-uploads

Upload here the files the agent gives you. A robot (the "Sort Uploads" workflow) places them
in their correct path on its own and empties this folder.

If a file is new, or there are several files with the same name, the agent will also give you a
`ROUTES.txt` file. Upload it along with the rest.

If files need to be deleted from the repository, the agent will give you a `DELETE.txt` (one path
per line). Upload it like the rest: the robot deletes those files and then deletes itself.

You can also upload a ZIP: the robot opens it and copies to the root only what does not exist yet
(without touching `.github/workflows`).
